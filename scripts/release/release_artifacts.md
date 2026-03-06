# Releasing Lucene-mongot Artifacts

## CDN release (signed)

Push a git tag matching `releases/mongot/<version>` to trigger a signed CDN release.
Evergreen will automatically build the modules, GPG-sign every JAR, POM, and Gradle
module metadata file with Garasign, and upload everything to the CDN origin bucket.

```shell
git tag releases/mongot/10.3.2-1
git push origin releases/mongot/10.3.2-1
```

**Version format**: Versions must use the `N.N.N-N` format (e.g. `10.3.2-1`), where
`N.N.N` is the upstream Lucene release and `-N` is the mongot patch number. Do **not**
use dots for the patch segment (e.g. `10.3.2.1`). Lucene's internal version-parsing
code assumes three numeric components; a four-component dotted version breaks
`org.apache.lucene.util.Version` comparisons and can cause runtime errors in codecs
and segment handling.

Signatures use the `.asc` extension (ASCII-armored GPG detached signatures) and are
uploaded alongside the artifacts they sign.

### Garasign credentials

The signing step authenticates to the `garasign-gpg` container image hosted on ECR
(`901841024863.dkr.ecr.us-east-1.amazonaws.com/release-infrastructure/garasign-gpg`).
Credentials are stored as Evergreen project variables: `garasign_username` and
`garasign_password`.

## Dev artifacts

Dev artifacts are signed and uploaded to the development S3 bucket:

- https://lucene-mongot-development.s3.us-west-1.amazonaws.com/lucene-mongot/maven/

These artifacts are used for testing mongot builds against unreleased Lucene changes
before a signed CDN release is cut.

To upload artifacts, run the `release_artifacts.sh` script from your release branch:

```shell
scripts/release/release_artifacts.sh --version 11.1.0-1
```

The Evergreen `version_id` is appended to form the actual Maven artifact version,
e.g. `11.1.0-1-69a79e2060729b0007bf3893`. This means the JARs and POMs themselves are
versioned as `lucene-core-11.1.0-1-69a79e2060729b0007bf3893.jar`, not `lucene-core-11.1.0-1.jar`.

To change which modules are published, edit `scripts/release/modules.conf`.

The artifact will not be uploaded if an artifact with the same name already exists in the bucket
(`skip_existing: true`).

## Using dev artifacts in mongot

For a working example, see the
[`demo_jar_from_lucene_mongot_development`](https://github.com/10gen/mongot/tree/demo_jar_from_lucene_mongot_development)
branch in mongot.

### 1. Find the Lucene version

Go to the Evergreen patch → `publish-dev` task →
[**Files** tab](https://spruce.corp.mongodb.com/task/lucene_mongot_ubuntu2204_large_publish_dev_patch_e5b39ea6a61157327b55b7bae940f2bd278d4d86_69a79e2060729b0007bf3893_26_03_04_02_51_14/files?execution=0).

The version is in the filename. For example, if a file is named
`lucene-backward-codecs-11.1.0-1-69a79e2060729b0007bf3893-javadoc.jar`,
then the version is `11.1.0-1-69a79e2060729b0007bf3893`.

### 2. Add the dev repository in `bazel/java/deps.bzl`

Add the dev S3 bucket as a Maven repository in the `maven_install` call:

```python
maven_install(
    artifacts = _mongot_java_artifacts(),
    repositories = [
        "https://lucene-mongot-development.s3.us-west-1.amazonaws.com/lucene-mongot/maven",
        "https://repo1.maven.org/maven2",
    ],
    ...
)
```

### 3. Update the version in `bazel/java/search_query_deps.bzl`

Change `_LUCENE_VERSION` to the full version from step 1:

```python
_LUCENE_VERSION = "11.1.0-1-69a79e2060729b0007bf3893"
```

### 4. Update dependencies

```shell
make deps.update
```

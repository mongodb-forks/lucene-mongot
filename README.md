<!--
    Licensed to the Apache Software Foundation (ASF) under one or more
    contributor license agreements.  See the NOTICE file distributed with
    this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
-->

# Lucene-Mongot

This is MongoDB's fork of [Apache Lucene](https://github.com/apache/lucene), maintained for use by
[mongot](https://github.com/mongodb/mongot/).

The `main` branch, development branches (e.g. `branch_10x`, `branch_10_3`, `branch_9_11`) and
release tags (e.g. `releases/lucene/10.3.2`, `releases/lucene/9.11.1`) track upstream Lucene. 
Mongot development branches (e.g. `mongot_9_11_1`) are created from uptream release tags and contain
Mongot commits. See [scripts/release/](./scripts/release/) for the tooling that creates these 
branches and their corresponding Evergreen projects.

# Apache Lucene

![Lucene Logo](https://lucene.apache.org/theme/images/lucene/lucene_logo_green_300.png?v=0e493d7a)

Apache Lucene is a high-performance, full-featured text search engine library
written in Java.

## Online Documentation

This README file only contains basic setup instructions. For more
comprehensive documentation, visit:

- Latest Releases: <https://lucene.apache.org/core/documentation.html>
- Nightly: <https://ci-builds.apache.org/job/Lucene/job/Lucene-Artifacts-main/javadoc/>
- New contributors should start by reading [Contributing Guide](./CONTRIBUTING.md)
- Build System Documentation: [help/](./help/)
- Migration Guide: [lucene/MIGRATE.md](./lucene/MIGRATE.md)

## Building

### Basic steps

1. Install JDK 25 using your package manager or download manually from
[OpenJDK](https://jdk.java.net/),
[Adoptium](https://adoptium.net/temurin/releases),
[Azul](https://www.azul.com/downloads/),
[Oracle](https://www.oracle.com/java/technologies/downloads/) or any other JDK provider.
2. Clone Lucene's git repository (or download the source distribution).
3. Run gradle launcher script (`gradlew`).

We'll assume that you know how to get and set up the JDK - if you don't, then we suggest starting at <https://jdk.java.net/> and learning more about Java, before returning to this README.

## Contributing

External contributions are currently not accepted in this repository. If you'd like to contribute to Lucene,
please see the upstream project at <https://github.com/apache/lucene>.

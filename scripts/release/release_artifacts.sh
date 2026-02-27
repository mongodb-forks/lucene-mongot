#!/usr/bin/env bash
set -euo pipefail

traperr() {
  echo "ERROR: ${BASH_SOURCE[1]} at about line ${BASH_LINENO[0]}"
}
set -o errtrace
trap traperr ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INIT_SCRIPT="${SCRIPT_DIR}/github-packages.init.gradle"

usage() {
  echo
  echo "Usage: ${BASH_SOURCE[0]} --version <version> --branch <branch> --modules <m1,m2,...>"
  echo
  echo "Builds and publishes specific Lucene modules from a mongot branch to GitHub Packages."
  echo
  echo "Options:"
  echo "  --version   Maven version string (e.g. 10.3.2.1)"
  echo "  --branch    Git branch to build from (e.g. mongot_10_3_2)"
  echo "  --modules   Comma-separated list of modules to publish, using the Maven"
  echo "              artifact suffix after 'lucene-' (e.g. core,analysis-common,facet)"
  echo
  echo "Examples:"
  echo "  ${BASH_SOURCE[0]} --version 10.3.2.1 --branch mongot_10_3_2 --modules core,analysis-common,facet"
  echo "  ${BASH_SOURCE[0]} --version 9.11.1.2 --branch mongot_9_11_1 --modules core,queries,sandbox"
  echo
  echo "Available modules (subset — any publishable lucene subproject works):"
  echo "  core, analysis-common, analysis-icu, analysis-kuromoji, analysis-morfologik,"
  echo "  analysis-nori, analysis-phonetic, analysis-smartcn, analysis-stempel,"
  echo "  backward-codecs, codecs, expressions, facet, highlighter, join, memory,"
  echo "  misc, queries, queryparser, sandbox, test-framework"
  echo
  echo "Requires GITHUB_TOKEN to be set."
  exit 1
}

log() {
  local status
  case "$1" in
    info)    shift; status="$(tput setaf 4)==>$(tput sgr0)" ;;
    ok)      shift; status="$(tput bold)[$(tput setaf 2)+$(tput sgr0)$(tput bold)]$(tput sgr0)" ;;
    error)   shift; status="$(tput bold)[$(tput setaf 1)-$(tput sgr0)$(tput bold)]$(tput sgr0)" ;;
    status)
      shift
      echo -n "$(tput setaf 2)==>$(tput sgr0)$(tput bold) "
      echo -n "$@"
      echo "$(tput sgr0)"
      return
      ;;
  esac
  echo "$status $@"
}

# Maps a Maven artifact suffix (e.g. "analysis-common") to a Gradle subproject
# path (e.g. ":lucene:analysis:common").
module_to_gradle_path() {
  local module="$1"
  if [[ "$module" == analysis-* ]]; then
    echo ":lucene:${module/analysis-/analysis:}"
  else
    echo ":lucene:${module}"
  fi
}

# --- Parse arguments ---

version=""
branch=""
modules_csv=""

while [ $# -gt 0 ]; do
  case "$1" in
    --version)  version="$2";      shift 2 ;;
    --branch)   branch="$2";       shift 2 ;;
    --modules)  modules_csv="$2";  shift 2 ;;
    --help|-h)  usage ;;
    *)
      log error "Unknown option: $1"
      usage
      ;;
  esac
done

# --- Validate arguments ---

if [ -z "$version" ]; then
  log error "Missing --version"
  usage
fi

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  log error "Version must start with major.minor.patch (got: ${version})"
  usage
fi

if [ -z "$branch" ]; then
  log error "Missing --branch"
  usage
fi

if [ -z "$modules_csv" ]; then
  log error "Missing --modules"
  usage
fi

IFS=',' read -ra modules <<< "$modules_csv"

if [ ${#modules[@]} -eq 0 ]; then
  log error "No modules specified."
  usage
fi

if [ -z "${GITHUB_TOKEN:-}" ]; then
  log error "GITHUB_TOKEN environment variable is not set."
  exit 1
fi

if [ ! -f "$INIT_SCRIPT" ]; then
  log error "Gradle init script not found at ${INIT_SCRIPT}"
  exit 1
fi

# --- Set up a temporary worktree ---

log status "Setting up worktree for branch '${branch}'..."
worktree_dir=$(mktemp -d)
cleanup() {
  if [ -d "$worktree_dir" ]; then
    log info "Cleaning up worktree at ${worktree_dir}..."
    git worktree remove --force "$worktree_dir" 2>/dev/null || rm -rf "$worktree_dir"
  fi
}
trap cleanup EXIT

git worktree add "$worktree_dir" "$branch"
log ok "Worktree created at ${worktree_dir}"

# --- Build Gradle task list ---

gradle_tasks=()
for module in "${modules[@]}"; do
  gradle_path=$(module_to_gradle_path "$module")
  task="${gradle_path}:publishJarsPublicationToGitHubPackagesRepository"
  gradle_tasks+=("$task")
  log info "Will publish: lucene-${module} (${gradle_path})"
done

# --- Publish ---

echo
log status "Publishing ${#modules[@]} module(s) as version ${version}..."

(
  cd "$worktree_dir"
  ./gradlew "${gradle_tasks[@]}" \
    --init-script "$INIT_SCRIPT" \
    -Pversion.release="$version"
)

# --- Tag the release ---

tag="v${version}"
echo
log status "Tagging branch '${branch}' as '${tag}'..."

if git rev-parse "${tag}" >/dev/null 2>&1; then
  log error "Tag '${tag}' already exists. Skipping tag creation."
else
  modules_list=$(IFS=','; echo "${modules[*]}")
  git tag -a "$tag" "$branch" \
    -m "Lucene ${version} — published modules: ${modules_list}"
  git push origin "$tag"
  log ok "Tag '${tag}' pushed to origin."
fi

echo
log ok "Published ${#modules[@]} module(s) to GitHub Packages:"
for module in "${modules[@]}"; do
  log ok "  org.apache.lucene:lucene-${module}:${version}"
done
echo
log ok "Packages: https://github.com/mongodb-forks/lucene-mongot/packages"

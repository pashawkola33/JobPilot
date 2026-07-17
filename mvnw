#!/bin/sh
set -eu
MVNW_VERSION=3.9.11
MVNW_DISTRIBUTION_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVNW_VERSION/apache-maven-$MVNW_VERSION-bin.tar.gz"
MVNW_PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MVNW_CACHE_ROOT="${MAVEN_USER_HOME:-$MVNW_PROJECT_DIR/.mvn}"
MVNW_BASE="$MVNW_CACHE_ROOT/wrapper/dists/apache-maven-$MVNW_VERSION-bin"
MVNW_HOME="$MVNW_BASE/apache-maven-$MVNW_VERSION"
MVNW_LOCAL_REPO="${MAVEN_REPO_LOCAL:-$MVNW_CACHE_ROOT/repository}"
if [ ! -x "$MVNW_HOME/bin/mvn" ]; then
  mkdir -p "$MVNW_BASE"
  MVNW_ARCHIVE="$MVNW_BASE/apache-maven-$MVNW_VERSION-bin.tar.gz"
  if [ ! -f "$MVNW_ARCHIVE" ]; then
    curl -fsSL "$MVNW_DISTRIBUTION_URL" -o "$MVNW_ARCHIVE"
  fi
  if [ ! -f "$MVNW_ARCHIVE.sha512" ]; then
    curl -fsSL "$MVNW_DISTRIBUTION_URL.sha512" -o "$MVNW_ARCHIVE.sha512"
  fi
  MVNW_EXPECTED_SHA=$(sed -n '1p' "$MVNW_ARCHIVE.sha512" | tr -d '[:space:]')
  MVNW_ACTUAL_SHA=$(shasum -a 512 "$MVNW_ARCHIVE" | awk '{print $1}')
  if [ "$MVNW_EXPECTED_SHA" != "$MVNW_ACTUAL_SHA" ]; then
    echo "Maven distribution checksum verification failed" >&2
    exit 1
  fi
  tar -xzf "$MVNW_ARCHIVE" -C "$MVNW_BASE"
fi
exec "$MVNW_HOME/bin/mvn" -Dmaven.repo.local="$MVNW_LOCAL_REPO" "$@"

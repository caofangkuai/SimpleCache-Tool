#!/usr/bin/env bash
# Build the Simple Cache library jar with javac + jar.
#
# Sources are compiled into a fresh directory so that removing a source file
# can never leave a stale class inside the jar.
set -euo pipefail
cd "$(dirname "$0")"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/simplecache-build.XXXXXX")"
javac -encoding UTF-8 -d "$BUILD_DIR" $(find src -name '*.java')
jar --create --file simplecache.jar -C "$BUILD_DIR" .
echo "build ok -> simplecache.jar (classes: $BUILD_DIR)"

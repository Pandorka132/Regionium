#!/bin/sh
set -eu

# Load local release credentials only when a local .env file exists.
# The file must contain shell-compatible KEY=value lines.
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

VERSION=${1:?Usage: scripts/release.sh <version>}
sed -i "s/0.0-SNAPSHOT/${VERSION}/" gradle.properties
./gradlew runDatagen runGameTestServer buildAndCollect publishModrinth -Pregionium.carpetTests --stacktrace

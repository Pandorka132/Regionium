#!/bin/sh
set -eu

# Always run Gradle from the project root, regardless of the caller's cwd.
cd "$(dirname "$0")/.."

# Load local release credentials only when a local .env file exists.
# The file must contain shell-compatible KEY=value lines.
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

VERSION=${1:?Usage: scripts/release.sh <version>}
case "$VERSION" in
    *[!0-9A-Za-z.+-]*|'')
        echo "Invalid release version: $VERSION" >&2
        exit 2
        ;;
esac

: "${MODRINTH_ID:?MODRINTH_ID must be set in .env or the environment}"
: "${MODRINTH_TOKEN:?MODRINTH_TOKEN must be set in .env or the environment}"
: "${CURSEFORGE_ID:?CURSEFORGE_ID must be set in .env or the environment}"
: "${CURSEFORGE_TOKEN:?CURSEFORGE_TOKEN must be set in .env or the environment}"

# Semantic Release runs this script during its prepare phase. Update the
# actual configured version key; do not rely on a stale snapshot placeholder.
sed -i "s/^mod\.version=.*/mod.version=${VERSION}/" gradle.properties

# Keep tests isolated from the production build/publish configuration so the
# optional Carpet test dependency is not present in the publishing invocation.
./gradlew :26.3-fabric:runGameTestServer -Pregionium.carpetTests --stacktrace
./gradlew :26.3-fabric:buildAndCollect :26.3-fabric:publishMods --stacktrace

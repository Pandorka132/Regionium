#!/bin/sh
set -eu

# Always run Gradle from the project root, regardless of the caller's cwd.
cd "$(dirname "$0")/.."

# Load the .env and export its variables to Gradle.
# The file must use shell-compatible KEY=value syntax.
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN must be set in .env or the environment}"
export ORG_GRADLE_PROJECT_MODRINTH_TOKEN="$MODRINTH_TOKEN"

VERSION=${1:?A release version is required}

echo "Replacing version with ${VERSION}"
sed -e "s/0.0-SNAPSHOT/${VERSION}/" -i gradle.properties

./gradlew runGameTestServer buildAndCollect publishModrinth -Pregionium.carpetTests --stacktrace

#!/bin/sh
set -eu

VERSION=${1:?A release version is required}

echo "Replacing version with ${VERSION}"
sed -e "s/0.0-SNAPSHOT/${VERSION}/" -i gradle.properties

./gradlew runDatagen runGameTestServer buildAndCollect publishMods --stacktrace

#!/usr/bin/env bash
# Builds the application image for linux/amd64 from a git tag (or the working tree with "dev"),
# and pulls the pinned base images for linux/amd64.
#   scripts/build-images.sh 1.1.0        # builds zonewatch/app:1.1.0 from tag v1.1.0
#   scripts/build-images.sh dev          # builds zonewatch/app:dev from the working tree
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?version, for example 1.1.0, or dev}"
PLATFORM="${PLATFORM:-linux/amd64}"
BASE_IMAGES=(postgres:16.4-alpine nginx:1.27.3-alpine)

for img in "${BASE_IMAGES[@]}"; do
  docker pull --platform "$PLATFORM" "$img"
done

if [ "$VERSION" = "dev" ]; then
  docker build --platform "$PLATFORM" --build-arg ZW_VERSION=dev -t zonewatch/app:dev .
else
  # build exactly what the tag holds, not the working tree
  git archive --format=tar "v$VERSION" | docker build --platform "$PLATFORM" --build-arg ZW_VERSION="$VERSION" -t "zonewatch/app:$VERSION" -
fi
docker image inspect "zonewatch/app:$VERSION" --format '{{.RepoTags}} {{.Os}}/{{.Architecture}}'

#!/usr/bin/env bash
# Produces the offline bundle in ./bundle from the tags v1.0.0 and v1.1.0:
#   zonewatch-images-1.0.0.tar.gz  app 1.0.0 + postgres + nginx   (docker save, linux/amd64)
#   zonewatch-images-1.1.0.tar.gz  app 1.1.0 + postgres + nginx
#   zonewatch-deploy-1.1.0.tar.gz  compose file, configuration templates, env files, INSTALL.md, docs
#   INSTALL.md                     the same instructions, readable before unpacking
#   SHA256SUMS                     sha256 of every other file
set -euo pipefail
cd "$(dirname "$0")/.."
PLATFORM=linux/amd64
OUT=bundle
VERSIONS=(1.0.0 1.1.0)
BASE_IMAGES=(postgres:16.4-alpine nginx:1.27.3-alpine)
SHA256="sha256sum"
command -v sha256sum >/dev/null || SHA256="shasum -a 256"

rm -rf "$OUT" && mkdir -p "$OUT"
for v in "${VERSIONS[@]}"; do
  scripts/build-images.sh "$v"
done
for v in "${VERSIONS[@]}"; do
  docker save --platform "$PLATFORM" "zonewatch/app:$v" "${BASE_IMAGES[@]}" | gzip -6 > "$OUT/zonewatch-images-$v.tar.gz"
done

# deployment files from the v1.1.0 tag (they cover v1.0.0, v1.1.0 and the upgrade)
STAGE=$(mktemp -d)
git archive v1.1.0 deploy INSTALL.md docs/RUNBOOK.md docs/CR-1.md pack/openapi.yaml | tar -x -C "$STAGE"
mkdir -p "$STAGE/zonewatch/docs"
cp -R "$STAGE/deploy/." "$STAGE/zonewatch/"
rm -f "$STAGE/zonewatch/zwctl"
cp "$STAGE/INSTALL.md" "$STAGE/zonewatch/INSTALL.md"
cp "$STAGE/docs/RUNBOOK.md" "$STAGE/docs/CR-1.md" "$STAGE/pack/openapi.yaml" "$STAGE/zonewatch/docs/"
tar -C "$STAGE" -czf "$OUT/zonewatch-deploy-1.1.0.tar.gz" zonewatch
cp "$STAGE/INSTALL.md" "$OUT/INSTALL.md"
rm -rf "$STAGE"

(cd "$OUT" && $SHA256 zonewatch-images-1.0.0.tar.gz zonewatch-images-1.1.0.tar.gz zonewatch-deploy-1.1.0.tar.gz INSTALL.md > SHA256SUMS)
ls -l "$OUT"
cat "$OUT/SHA256SUMS"

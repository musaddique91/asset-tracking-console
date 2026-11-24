# Building and patching inside the air gap

Today the bundle is built on a connected machine (`scripts/build-bundle.sh`) and carried in. Where
no public registry can be reached at all, the build needs its inputs mirrored once and refreshed
through the same controlled transfer that carries the bundle:

- **An internal registry** (for example a Harbor or a plain `registry:2` container) holding the
  pinned base images by digest: `postgres:16.4-alpine`, `nginx:1.27.3-alpine`,
  `eclipse-temurin:21.0.5_11-jre-jammy`, `maven:3.9.9-eclipse-temurin-21`, `node:22.12.0-alpine`.
  The Dockerfile's `FROM` lines are switched to that registry with a build argument.
- **Dependency mirrors**: a Maven repository proxy (Nexus/Artifactory, or a `~/.m2` snapshot produced
  by `mvn dependency:go-offline`) and an npm mirror (or the `node_modules` cache from `npm ci`
  against the committed `package-lock.json`). `settings.xml` and `.npmrc` point at the mirrors.
  Versions are pinned in `pom.xml` and the lock file, so the same inputs give the same build.
- **Imports are reviewed**: every transfer into the gap carries a manifest with checksums (and
  signatures where the source provides them); dependency and image updates are scanned
  (for example Trivy/Grype with an offline vulnerability database imported the same way)
  before they enter the mirror.
- **Patching** is then a normal release: bump the base image digest or dependency version, rebuild
  inside the gap, produce `zonewatch-images-<version>.tar.gz` and roll it out one instance at a time
  as in INSTALL.md. OS-level CVEs in base images are fixed by importing the patched base image,
  never by `apt-get` inside the gap. Database minor upgrades follow the same path (new
  `postgres:16.x` image, restart of the `db` service in a maintenance window).

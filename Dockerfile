# syntax=docker/dockerfile:1
# One image per release: the API instance, the one-shot migration and maintenance jobs, and the
# console's static files. Build stages run on the build machine's own architecture (bytecode and
# static files are architecture-independent); the runtime stage is linux/amd64.

FROM --platform=$BUILDPLATFORM node:22.12.0-alpine AS console
WORKDIR /console
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21.0.5_11-jre-jammy
ARG ZW_VERSION=dev
LABEL org.opencontainers.image.version=$ZW_VERSION
RUN groupadd --system --gid 10001 zw && useradd --system --uid 10001 --gid zw --no-create-home zw
WORKDIR /app
COPY --from=backend /backend/target/zone-watch.jar /app/app.jar
COPY --from=console /console/dist /app/console
COPY deploy/zwctl /app/bin/zwctl
ENV ZW_VERSION=$ZW_VERSION \
    ZW_CONSOLE_DIR=/app/console \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

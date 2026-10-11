# Multi-stage build for Flash Enterprise Hub standalone server
FROM gradle:8.10-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN ./gradlew :server:installDist --no-daemon

# Minimal production runtime container with Eclipse Temurin JRE 21 on Alpine
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install curl for container healthchecking
RUN apk add --no-cache curl

COPY --from=build /home/gradle/src/server/build/install/server/ /app/

# Environment defaults
ENV FLASH_HOST=0.0.0.0
ENV FLASH_PORT=8443
ENV FLASH_ADMIN_PORT=8444
ENV FLASH_DATA_DIR=/data
ENV FLASH_TLS_MODE=proxy

EXPOSE 8443 8444

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://127.0.0.1:8443/health || exit 1

ENTRYPOINT ["/app/bin/server"]

# Flash Enterprise Hub: multi-stage image (SEC-16).
#
# Build from the REPOSITORY ROOT (the hub depends on the shared core modules):
#   docker build -f server/Dockerfile -t flash-hub:local .
#
# What this image contains: the hub, its delegate key (created on first start under /data) and NOTHING that can
# mint organisation identities. The organisation ROOT key is never in the image or on the server (SEC-04, ADR-111):
# create it offline with `keytool init-root`, issue the hub a delegation with `keytool issue-delegation`, and drop
# the resulting delegation.cert into the data volume (or point FLASH_DELEGATION_FILE at it).
#
# Base images are pinned to a release tag. For a production build pin them by digest as well
# (`image@sha256:...`, `docker buildx imagetools inspect <image>`), then bump them deliberately.

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN ./gradlew :server:installDist --no-daemon --configure-on-demand

FROM eclipse-temurin:21.0.5_11-jre-alpine

# A dedicated unprivileged user. /app is root-owned and read-only to it; /data is the ONLY writable path.
RUN addgroup -g 10001 -S flash \
 && adduser -u 10001 -S -G flash -h /nonexistent -s /sbin/nologin flash \
 && mkdir -p /data \
 && chown flash:flash /data \
 && chmod 700 /data

WORKDIR /app
COPY --from=build --chown=root:root /home/gradle/src/server/build/install/server/ /app/

ENV FLASH_HOST=0.0.0.0 \
    FLASH_PORT=8443 \
    FLASH_ADMIN_HOST=0.0.0.0 \
    FLASH_ADMIN_PORT=8444 \
    FLASH_DATA_DIR=/data \
    FLASH_TLS_MODE=proxy \
    FLASH_REQUIRE_ROOT_PIN=true
# FLASH_REQUIRE_ROOT_PIN=true (owner decision 2026-10-13, ADR-127): the image refuses to adopt a root key on first use. Set
# FLASH_ROOT_FINGERPRINT to the fingerprint `keytool init-root` printed, or enrolment stays closed. For a local trial run
# `docker run -e FLASH_REQUIRE_ROOT_PIN=false ...`. A bare `./gradlew :server:run` / installDist start keeps the old default (false).

# 8443: relay + REST (publish this one, behind your TLS proxy).
# 8444: the management listener with the admin console. Inside the container it must listen on all interfaces so the
#       host can reach it, so DO NOT publish it to the network: bind it to loopback on the host
#       (`-p 127.0.0.1:8444:8444`) and reach it through an SSH tunnel or an authenticated proxy.
EXPOSE 8443

VOLUME ["/data"]
USER flash:flash

# The hub probes itself (`server healthcheck`): no curl in the image, and it works in every TLS mode.
HEALTHCHECK --interval=60s --timeout=10s --start-period=30s --retries=3 \
  CMD ["/app/bin/server", "healthcheck"]

ENTRYPOINT ["/app/bin/server"]
CMD ["serve"]

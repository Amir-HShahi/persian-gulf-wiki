# syntax=docker/dockerfile:1

# Build context is the repository root, matching core/Dockerfile, so the deploy
# workflow can keep passing the same context for every image it builds.

FROM golang:1.26-trixie AS build
WORKDIR /app

# libvips is a C library reached through cgo, so it is needed to compile and
# not only to run. trixie carries 8.16, which is well past what govips needs.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libvips-dev \
        pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Dependencies resolve in their own layer so a source-only change does not
# re-download the module graph.
COPY submission-pipeline/go.mod submission-pipeline/go.sum ./
RUN go mod download

COPY submission-pipeline/cmd ./cmd
COPY submission-pipeline/internal ./internal

# The vips tag selects the real implementation in internal/imagepipe. Without
# it the binary builds but refuses image work, which is the correct behaviour
# on a developer machine and the wrong one in production.
ARG APP_VERSION=0.0.0
RUN CGO_ENABLED=1 go build \
        -tags vips \
        -ldflags "-s -w -X main.version=${APP_VERSION}" \
        -o /app/worker \
        ./cmd/worker


FROM debian:trixie-slim

# libvips-tools pulls in the matching runtime library, which avoids hardcoding
# a soname that changes between releases, and leaves the vips CLI in the image
# for diagnosing a bad file in production.
#
# Debian ships HEIF codecs as separate plugins because of the patent position
# on HEVC, so libheif alone decodes nothing. libde265 is what actually reads
# the HEIC a phone produces; without it every iPhone upload is rejected as
# unreadable.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libvips-tools \
        libheif-plugin-libde265 \
        libheif-plugin-aomdec \
        ca-certificates \
        wget \
    && rm -rf /var/lib/apt/lists/*

# Fail the build rather than ship an image that rejects the formats it exists
# to accept. A missing loader is otherwise invisible until a real submission
# comes in and is written off as corrupt.
RUN vips -l 2>/dev/null | grep -q heifload \
        || (echo "FATAL: libvips has no HEIF loader" >&2; exit 1)

RUN groupadd --system pipeline && useradd --system --gid pipeline pipeline
WORKDIR /app
COPY --from=build /app/worker ./worker

USER pipeline:pipeline
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -qO- http://localhost:8080/healthz || exit 1
ENTRYPOINT ["./worker"]

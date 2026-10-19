# Test-only replacement for the inaccessible upstream release image.
# The manifest pin supports native Linux builds on amd64 and arm64.
FROM golang:1.24.2-bookworm@sha256:79390b5e5af9ee6e7b1173ee3eac7fadf6751a545297672916b59bfa0ecf6f71 AS build
ENV GOTOOLCHAIN=local CGO_ENABLED=0
WORKDIR /src
RUN curl --fail --show-error --silent --location --max-time 120 \
      https://codeload.github.com/minio/minio/tar.gz/07c3a429bfed433e49018cb0f78a52145d4bedeb \
      --output /tmp/minio-source.tar.gz \
    && printf '%s  %s\n' \
      8819e3e7817e46b7b3798f8f200ead208562e571563c2e040352378031abe9f2 \
      /tmp/minio-source.tar.gz | sha256sum --check --strict \
    && tar --extract --gzip --file /tmp/minio-source.tar.gz --strip-components=1 \
    && test "$(go env GOVERSION)" = go1.24.2 \
    && sha256sum go.mod go.sum > /tmp/go-inputs.sha256 \
    && go mod download \
    && go mod verify \
    && sha256sum --check --strict /tmp/go-inputs.sha256
# Fixed flags replace gen-ldflags.go, which requires Git metadata absent in an archive.
# This is a development fixture built from that release, not an upstream binary.
RUN go build -mod=readonly -trimpath -buildvcs=false -p 2 \
      -ldflags '-s -w -X github.com/minio/minio/cmd.Version=2025-09-07T16:13:09Z -X github.com/minio/minio/cmd.ReleaseTag=DEVELOPMENT.2025-09-07T16-13-09Z -X github.com/minio/minio/cmd.CommitID=07c3a429bfed433e49018cb0f78a52145d4bedeb -X github.com/minio/minio/cmd.ShortCommitID=07c3a429bfed4 -X github.com/minio/minio/cmd.CopyrightYear=2025' \
      -o /out/minio . \
    && sha256sum --check --strict /tmp/go-inputs.sha256 \
    && /out/minio --version \
    && sha256sum /out/minio \
    && mkdir -p /out/data /out/tmp \
    && chmod 1777 /out/tmp

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="07c3a429bfed433e49018cb0f78a52145d4bedeb" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.description="Ephemeral audit integration test fixture; not for deployment"
COPY --from=build /out/minio /minio
COPY --from=build /src/LICENSE /LICENSE
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/data /data
COPY --from=build /out/tmp /tmp
ENV HOME=/tmp/minio-home MINIO_UPDATE=off
EXPOSE 9000
ENTRYPOINT ["/minio"]

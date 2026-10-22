# CI-only MinIO fixture for audit-retention-worker ArchiveServiceIntegrationTest
# (Object Lock COMPLIANCE + versioning, ADR-0042). gitops#3888.
#
# Upstream MinIO community distribution is gone: quay.io/minio/minio and
# docker.io/minio/minio no longer serve images (401 / repository removed) and
# dl.min.io answers 410 Gone for all community binaries. The source repository
# (github.com/minio/minio, archived) still exists, so the fixture is built from
# the exact commit behind RELEASE.2025-09-07T16-13-09Z — the release the test
# was written against — with no registry dependency.
FROM golang:1.24-alpine AS build
RUN apk add --no-cache git
ARG MINIO_COMMIT=07c3a429bfed433e49018cb0f78a52145d4bedeb
ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z
WORKDIR /src
RUN git init -q . && git remote add origin https://github.com/minio/minio \
    && git fetch -q --depth 1 origin "${MINIO_COMMIT}" && git checkout -q FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${MINIO_COMMIT}"
RUN CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X github.com/minio/minio/cmd.ReleaseTag=${MINIO_RELEASE} -X github.com/minio/minio/cmd.CommitID=${MINIO_COMMIT}" \
      -o /out/minio .

FROM alpine:3.20
COPY --from=build /out/minio /usr/bin/minio
RUN mkdir -p /data
EXPOSE 9000
ENTRYPOINT ["/usr/bin/minio"]

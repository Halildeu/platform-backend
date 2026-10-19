# Source-built MinIO test fixture

The pinned Quay release image stopped allowing anonymous pulls (CI reported
`unauthorized` before its pull timeout). Increasing the timeout cannot repair
that failure. This fixture builds the same release's official source instead.
It is used only by `ArchiveServiceIntegrationTest`, both locally and in CI.
No image is published and no deployed storage service changes.

Pins verified on 28 September 2026:

- Official signed tag `RELEASE.2025-09-07T16-13-09Z` resolves to commit
  `07c3a429bfed433e49018cb0f78a52145d4bedeb` (GitHub verified tag and commit).
- The official commit archive SHA256 is
  `8819e3e7817e46b7b3798f8f200ead208562e571563c2e040352378031abe9f2`.
- Docker Official Image `golang:1.24.2-bookworm` multi-platform index SHA256 is
  `79390b5e5af9ee6e7b1173ee3eac7fadf6751a545297672916b59bfa0ecf6f71`.
  It pins the compiler and build environment. `GOTOOLCHAIN=local` forbids an
  automatic compiler download; upstream module checksums remain enforced.

The Dockerfile verifies the archive before extraction and verifies dependencies
against the pinned source's `go.sum`. A static native Linux binary runs directly
in scratch, with its license and CA roots. There is no upstream MinIO image,
shell entrypoint, package-manager update, BuildKit-only instruction, or registry
login. Fixed build metadata identifies this as a development source fixture.
Its binary hash and version are printed during the build for evidence.

Run the existing suite with Docker available:

```text
./mvnw -pl audit-retention-worker test
```

The first run compiles MinIO and requires network access to the pinned source,
Docker Official Images and Go modules. Docker layers can accelerate later runs.
Testcontainers owns the temporary image and containers. Build or startup errors
fail the tests; there is no mock fallback or skip-on-failure path. The existing
PostgreSQL and Object Lock COMPLIANCE, versioning, delete-denied and archive
round-trip checks remain unchanged. Compilation alone is not acceptance: the
full real-container suite must pass before this fixture is accepted.

This is not a production security or dependency upgrade. Pin changes require
source/hash verification and the full integration suite again. Do not deploy or
publish the test image.

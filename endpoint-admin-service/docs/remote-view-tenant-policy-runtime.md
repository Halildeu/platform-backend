# Remote-view tenant policy runtime

This runtime binds one immutable tenant privacy policy to each attended remote-view session. It is disabled by
default and must remain disabled until the endpoint agent advertises
`remote-view-session-policy-envelope-v1` and the GitOps authority artifacts are mounted.

## Authority and storage

- GitOps owns the strict tenant-policy, baseline and session-envelope schemas plus the platform baseline.
- The backend reads those files at startup, validates the baseline, and refuses startup on a missing or invalid
  artifact. Production artifacts are never copied into the application image.
- Policy JSON crosses a dedicated strict parser before generic approval storage. Duplicate keys, trailing tokens,
  unknown proposal fields, floating-point values, non-interoperable integers and invalid Unicode are rejected.
- Publication accepts `approvalId` only. The approved semantic JSON value is canonicalized with RFC 8785 and must
  match the immutable intake digest.
- Publication, intake and revocation ledgers are append-only. PostgreSQL triggers reject update, delete and
  truncate operations. A content-addressed predecessor constraint prevents concurrent publication forks.
- Revocation uses a dedicated DELETE proposal bound to the exact canonical published value. Applying a revocation
  accepts `approvalId` only and never falls back to an older policy.

## Signing keys

Session policy envelopes use a dedicated Ed25519 key family. Do not reuse TLS, JWT, device or operation-permit
keys. Private material is read only from mounted PKCS#8 DER files; public material is X.509 DER.

```yaml
remote-view-policy:
  enabled: false
  tenant-policy-schema-path: /etc/platform/remote-view/tenant-policy.schema.json
  baseline-schema-path: /etc/platform/remote-view/baseline.schema.json
  envelope-schema-path: /etc/platform/remote-view/session-policy-envelope.schema.json
  baseline-path: /etc/platform/remote-view/platform-baseline.json
  envelope-ttl-seconds: 300
  active-key-id: remote-view-policy-2026-01
  signing-keys:
    - key-id: remote-view-policy-2026-01
      private-key-pkcs8-path: /var/run/secrets/remote-view-policy/active-private.der
      public-key-der-path: /var/run/secrets/remote-view-policy/active-public.der
    - key-id: remote-view-policy-2025-12
      public-key-der-path: /var/run/secrets/remote-view-policy/previous-public.der
      verify-until: 2026-08-01T00:00:00Z
  revoked-key-ids: []
```

Only the active key may have a private-key path. Every overlap verification key requires a bounded
`verify-until`. An unknown, expired or revoked key fails verification. Startup signs and verifies a probe so a
mismatched key pair cannot enter service.

`envelope-ttl-seconds` has a hard 60-second startup floor. The runtime still clamps it to the remaining consent
prompt, tenant-policy and platform-baseline limits.

## Session and permit binding

When policy mode is enabled:

1. The broker requires a fresh agent HELLO containing `remote-view-session-policy-envelope-v1`.
2. The tenant policy is selected without expiry or revocation fallback and all source digests are recomputed.
3. A fresh envelope is bound to session, tenant, device, nonce and a TTL clamped by prompt, policy, baseline and
   server limits.
4. The broker records the envelope digest before sending the consent prompt. Audit failure kills the session.
5. Every operation uses permit v2 and signs the exact session-envelope digest. Legacy permit v1 remains available
   only while policy mode is disabled.

## Rollout order

1. Publish digest-pinned authority artifacts and key mounts through the test GitOps overlay.
2. Roll out an endpoint agent that validates the envelope and advertises the feature.
3. Enable the backend flag in test only. Missing policy, unsupported agent or invalid signing configuration must
   deny a session without delivering a legacy prompt.
4. Create, independently approve and publish a bounded-test tenant policy through the dedicated APIs.
5. Capture positive and negative runtime evidence for tenant isolation, digest drift, expiry, revocation,
   same-session binding, notice mismatch and agent downgrade.
6. Treat production enablement as a separate evidence-gated GitOps change. This implementation does not activate
   production by itself.

## Baseline rotation

Published policies are intentionally pinned to one baseline digest. Replacing the mounted baseline invalidates
every publication that still names the old digest; the runtime denies rather than silently inheriting a new legal
or safety meaning. Therefore a baseline rotation requires an explicit maintenance window, tenant policy
re-approval/re-publication plan and rollback artifact. Do not rotate an enabled environment until an atomic
multi-tenant migration procedure has been proven in test.

The additive protobuf details are documented in [remote-bridge-wire-contract.md](remote-bridge-wire-contract.md).

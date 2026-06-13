# RB — Faz 22.6 D10 Acceptance-Gate Evidence Map (first live pilot)

> **Amaç:** ADR-0034 §11/D10 acceptance gate'inin 14 kriterini (3 original-gate + 11 expanded must-land) **kanıta** bağlar: her kriter için **agent-completable evidence (kod/test/PR ref — DONE)**, **agent-completable gap (yazılacak test/slice + spec)**, veya **owner-gated (live producer/infra/legal — owner-execution step)** ayrımı.
>
> **Neden bu doküman:** D10 11/11 = ilk canlı pilot, *bir bütün olarak* owner-gated (4-rol KVKK + fiziksel pilot PC + live producer'lar). Ama kriterlerin **çoğu agent-completable EVIDENCE** içeriyor — bu map onları haritalar (HARD RULE "Tam Otonom": owner-gated residual için agent-driven path). "Agent-completable kod kalmadı" değil; **birkaç spesifik agent-gap** var (aşağıda 🟡).
>
> **Kaynak:** ADR-0034 §11 (owner-signed 2026-06-11) · ADR-0033 (broker/threat-model) · gitops #1388 (LIFTED, engineering) · backend #510 (22.6 umbrella).
> **Statü lejantı:** ✅ agent-evidence DONE · 🟡 agent-completable GAP (yazılabilir) · 🔒 owner-gated (live producer / infra / legal / fiziksel).

---

## Original gate (ADR-0034 §11)

| # | Kriter | Statü | Evidence / Gap / Owner-step |
|---|---|---|---|
| O1 | ADR-0033 ACCEPTED | ✅ | ADR-0034 owner-signed 2026-06-11 (§13, 4-rol); #1388 engineering gate LIFTED. |
| O2a | Broker negative: **capability-mismatch deny** (fail-closed) | ✅ | `RemoteBridgeBrokerTest.aNonPilotOperationIsDeniedEvenIfACapabilityWouldPermitIt` + `theEngineDryRunMatrixDeniesEachMissingPrecondition` (DENY, no permit). |
| O2b | Broker negative: **recorder-unavailable deny** (fail-closed) | ✅ | `RemoteBridgeBrokerTest.aRecordingFailureBlocksPermitIssuanceButNotAKill` (`recording-failed` → no permit; kill still fires). |
| O2c | Broker negative: **expired-replayed token deny** (fail-closed) | 🟡 | Parça: consent-lease expiry ✅ (`theConsentLeaseMustBeActive` / `aLateConsentIsRefused` / `aZeroOrPastExpiryGrantIsRefused`); jti/token replay → B2 atomic jti store (#541). **GAP:** broker↔jti-store **integration** negatif testi (expired+replayed permit token → DENY uniform) henüz tek akışta haritalanmadı. |
| O2d | Broker negative: **self-approval deny** (approver≠requester) | 🟡 | **GAP/verify:** maker≠checker dual-control (ADR-0033 §, D7 "Maker ≠ checker"). Broker-level'da approver≠requester check + negatif test var mı doğrula; yoksa **dual-control canonicalization slice** (bkz. E10). Memory notu: OpenFGA approver≠requester enforce EDEMEZ → app-level kanonikalizasyon gerek. |
| O3 | Recording fail-closed: **no `ACTIVE` without `RECORDING_READY`** | 🟡 | Parça: `aRecordingFailureBlocksPermit` ✅ (recording-fail → no permit). **GAP/verify:** state-machine `ACTIVATE` geçişinin `RECORDING_READY` ön-koşulunu zorladığı **explicit** negatif testi (RECORDING_READY yoksa ACTIVE'e geçemez). |
| O4 | D29-EA acceptance (Up ≠ Functional ≠ Secured ayrı kanıt) | 🔒 | **Owner/infra:** broker henüz cluster'a deploy edilmedi (disabled-by-default). Live D29-EA = deploy + 3-katman kanıt (Up/Functional/Secured). Owner-gated (deploy + acceptance). |

---

## Expanded must-land (ADR-0034 §11, red-team absorb — her biri olmadan pilot BLOCKED)

| # | Kriter | Statü | Evidence / Gap / Owner-step |
|---|---|---|---|
| E1 | Continuous re-eval + real-time kill (revoke→dead within SLO) | 🟡 + 🔒 | ✅ kill mekaniği: `ControlStreamRegistry.killPeer` (CONTROL Envelope.kill, sub-second, ayrı HTTP/2 stream) + duress→KILL (`duressTerminatesTheSession`) + LOCAL_ABORT/indicator-loss kill (`BrokerControlPlaneTest`). **GAP:** heartbeat re-validation → revoke → kill-within-SLO **negatif testi**. **🔒:** live heartbeat producer (agent). |
| E2 | Out-of-band signed audit/recording sink (append-only, hash-chained, WORM; broker-compromised'da doğrulanabilir) | ✅ + 🔒 | ✅ `DurableRemoteBridgeAuditSink` (#591, broker-independent `@Qualifier`, WORM session_recording_entry + hash-chain + RecordingAnchorSigner; record-before-permit ADR-0034 §6). **🔒:** live WORM object-lock storage + broker-compromised integrity-verify drill. |
| E3 | mTLS + non-exportable (TPM/HSM) cert-bound token + PKI lifecycle (CRL/OCSP/rotation) + trusted/monotonic clock | ✅ + 🔒 | ✅ mTLS (T-2c `TlsServerCredentials clientAuth=REQUIRE`, fail-closed) + cert-bound token (B1.1 #549/#550, certBound precondition + atomic status). **🔒:** TPM/HSM non-exportable key (donanım) + CRL/OCSP live PKI feed + trusted-clock infra. |
| E4 | Atomic distributed jti store (Redis SETNX/DB-unique) under concurrency + uniform `DENIED` constant-time + layered rate-limit | ✅ + 🟡 | ✅ B2 atomic jti lifecycle store (#541, DB-CAS) + constant-time compare primitifleri. **GAP:** uniform-`DENIED` constant-time wire-response + rate-limit'in **oracle/enumeration/retry-DoS yok** negatif testi tek akışta. **🔒:** live Redis/DB concurrency load. |
| E5 | Agent attestation depth (SBOM + SLSA + reproducible build + runtime binary-hash + cert posture, auto-rollback) | 🔒 | **Owner/build-pipeline + live agent** (platform-agent repo + CI SBOM/SLSA). `agentAttestation` verifier wire'lı (`AttestationVerifier` consumed); evidence **producer** owner-gated. |
| E6 | VIEW_ONLY exfil controls (endpoint DLP/screen-masking, watermark, 'remote-support active' indicator, local-abort, per-session content policy) | 🔒 | **Endpoint-side (platform-agent repo).** Broker tarafında VIEW_ONLY capability-pin ✅ (PILOT_ALLOWED); DLP/masking/watermark agent UI'da, owner-gated. |
| E7 | Endpoint-user coercion UX (visible indicator + always-available local kill + revocable-mid-session consent) | ✅ + 🔒 | ✅ broker tarafı: LOCAL_ABORT → kill (`localAbortAbortsTheLeaseAndKills`) + indicator-loss → kill (`indicatorLossAbortsTheLease...`) + revocable consent lease (clamp/shorten). **🔒:** endpoint UI indicator/kill button (platform-agent). |
| E8 | Broker hardening (separate deployment, NetworkPolicy + per-session egress ACL + namespace isolation, no ambient admin creds, secrets separation) | 🟡 + 🔒 | **GAP (gitops, agent-completable):** broker Deployment + NetworkPolicy (çift-yön) + egress ACL + namespace isolation manifest'leri (test-overlay disabled-by-default). **🔒:** live deploy + secrets seed (operator). |
| E9 | Operator-channel hardening (separate auth, FIDO2/device-posture, ws origin/CSRF, per-channel nonce, no bearer in URL/logs, re-auth/per-action MFA) | ✅ + 🟡 | ✅ **çoğu MİNE:** FIDO2/WebAuthn step-up (D #598-604, UP/UV/rpIdHash/JCA) + per-action MFA (step-up challenge/verify) + bearer **header'dan, URL'den DEĞİL** (`OperatorCredentialExtractor`) + no-verdict-oracle/no-bearer-in-logs (redaction) + separate operator auth (`OperatorAuthenticator`, tenant-scoped). **GAP:** operator REST için origin/CSRF + per-channel nonce **explicit** kontrolü/testi. |
| E10 | IAM identity canonicalization for dual-control (alias/proxy/service-account resolved before approver≠requester) + approval-fatigue limits | 🟡 | **GAP (agent-completable slice):** dual-control kanonikalizasyon (alias/proxy/SA → canonical identity) + approver≠requester enforce + approval-fatigue limit. OpenFGA bunu enforce edemez (memory) → app-level. **En net agent-completable D10 boşluğu.** |
| E11 | Red-team drill report (broker-compromise sim, jti replay, recorder-down→fail-closed, token theft, NTP skew, key leak/rotation — all pass) | 🟡 + 🔒 | **GAP (agent-completable SIM testleri):** jti-replay sim, recorder-down→fail-closed sim, NTP-skew→TTL-not-defeatable sim — **adversarial unit/integration test suite**. **🔒:** live red-team **drill** (insan, çalışan sisteme karşı) + rapor. |

---

## Özet — D10 11/11 statü dağılımı

- **✅ agent-evidence DONE (tam/parça):** O1, O2a, O2b, E2, E3, E7, E9 + parça O2c/O3/E1/E4 (bu oturum slice-4c + D step-up + B1 + B2 + durable-sink 23 PR'ı). Bridge suite **271 test** bu kanıtın gövdesi.
- **🟡 agent-completable GAP (yazılabilir, owner-gated DEĞİL):** O2c (broker↔jti integration neg-test), O2d/E10 (**dual-control approver≠requester** — en net slice), O3 (RECORDING_READY→ACTIVE explicit neg-test), E1 (revoke→kill-SLO neg-test), E4 (uniform-DENIED constant-time + rate-limit neg-test), E8 (broker NetworkPolicy/egress manifest'leri — gitops), E9 (operator REST CSRF/origin/nonce), E11 (red-team SIM test suite).
- **🔒 owner-gated (live producer / infra / legal / fiziksel):** O4 (D29-EA live deploy), E3-kısmi (TPM/HSM + CRL/OCSP + trusted-clock), E5 (agent SBOM/SLSA + live attestation producer), E6 (endpoint DLP/masking — platform-agent), E7-kısmi (endpoint UI — platform-agent), E8-kısmi (live deploy + secrets), E11-kısmi (live red-team drill) + **4-rol KVKK pilot kickoff + 2-5 fiziksel IT-owned PC + named roster** (D7).

**Sonuç:** D10 11/11 "hepsi owner-gated" DEĞİL. Substantial evidence **kurulu** (23 PR + 271 test); **~8 agent-completable gap** kaldı (yukarıda 🟡) — en yüksek değerli + net olanı **E10 dual-control (approver≠requester) canonicalization slice**. Geri kalan owner-gated (live producer/infra/legal/fiziksel).

## Sıradaki agent-completable D10 slice önceliği

1. **E10 dual-control** (approver≠requester canonicalization + approval-fatigue) — en net, broker authz çekirdeği, agent-completable.
2. **E8 broker hardening manifest'leri** (gitops: Deployment + NetworkPolicy + egress ACL, disabled-by-default).
3. **E11 red-team SIM test suite** (jti-replay / recorder-down→fail-closed / NTP-skew adversarial).
4. **O2c/O2d/O3/E1/E4/E9 explicit negatif testleri** (mevcut fail-closed davranışları D10-evidence olarak adlandır + boşlukları kapat).

> Bu map güncel tutulmalı: her D10-gap slice merge oldukça statüsü ✅'e çevrilir; tüm 🟡'ler ✅/🔒 olunca agent-completable D10 yüzeyi tükenir, kalan saf owner-gated pilot kickoff'tur.

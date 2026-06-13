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
| O2d | Broker negative: **self-approval deny** (approver≠requester) | ✅ + 🟡 | ✅ **raw** approver≠requester (`RemoteSessionAuthz` + `RemoteSessionNegativeTest`) **+ canonicalization FOUNDATION** (#616: `approverDistinctFromRequesterCanonical` — alias/SA/proxy self-approval DENIED). **🟡:** canonical resolver'ı live approval-flow'da consume etme (integration). |
| O3 | Recording fail-closed: **no `ACTIVE` without `RECORDING_READY`** | ✅ | `aRecordingFailureBlocksPermit` ✅ (recording-fail → no permit) + **state-machine explicit**: `RemoteSessionStateMachineTest` — ACTIVE yanlış source-state'ten `canActivate`=false; recorder eksik → `FAILED_RECORDING` (RECORDING_READY olmadan ACTIVE'e geçilemez). İsteğe bağlı dar ek: service/e2e activation-path regression. |
| O4 | D29-EA acceptance (Up ≠ Functional ≠ Secured ayrı kanıt) | 🔒 | **Owner/infra:** broker henüz cluster'a deploy edilmedi (disabled-by-default). Live D29-EA = deploy + 3-katman kanıt (Up/Functional/Secured). Owner-gated (deploy + acceptance). |

---

## Expanded must-land (ADR-0034 §11, red-team absorb — her biri olmadan pilot BLOCKED)

| # | Kriter | Statü | Evidence / Gap / Owner-step |
|---|---|---|---|
| E1 | Continuous re-eval + real-time kill (revoke→dead within SLO) | 🟡 + 🔒 | ✅ kill mekaniği: `ControlStreamRegistry.killPeer` (CONTROL Envelope.kill, sub-second, ayrı HTTP/2 stream) + duress→KILL (`duressTerminatesTheSession`) + LOCAL_ABORT/indicator-loss kill (`BrokerControlPlaneTest`). **GAP:** heartbeat re-validation → revoke → kill-within-SLO **negatif testi**. **🔒:** live heartbeat producer (agent). |
| E2 | Out-of-band signed audit/recording sink (append-only, hash-chained, WORM; broker-compromised'da doğrulanabilir) | ✅ + 🔒 | ✅ `DurableRemoteBridgeAuditSink` (#591, broker-independent `@Qualifier`, WORM session_recording_entry + hash-chain + RecordingAnchorSigner; record-before-permit ADR-0034 §6). **🔒:** live WORM object-lock storage + broker-compromised integrity-verify drill. |
| E3 | mTLS + non-exportable (TPM/HSM) cert-bound token + PKI lifecycle (CRL/OCSP/rotation) + trusted/monotonic clock | ✅ + 🔒 | ✅ mTLS (T-2c `TlsServerCredentials clientAuth=REQUIRE`, fail-closed) + cert-bound token (B1.1 #549/#550, certBound precondition + atomic status). **🔒:** TPM/HSM non-exportable key (donanım) + CRL/OCSP live PKI feed + trusted-clock infra. |
| E4 | Atomic distributed jti store (Redis SETNX/DB-unique) under concurrency + uniform `DENIED` constant-time + layered rate-limit | ✅ + 🟡 + 🔒 | ✅ B2 atomic jti lifecycle store (#541, DB-CAS) + uniform-`DENIED`/rate-limit primitifleri+testleri (`RemoteSessionNegativeTest` + `RemoteAccessRateLimiterTest`). **🟡 GAP:** integrated operator/broker wire-response timing/oracle **regression** testi. **🔒:** live high-rate/timing/load + ingress enforcement (infra). |
| E5 | Agent attestation depth (SBOM + SLSA + reproducible build + runtime binary-hash + cert posture, auto-rollback) | 🔒 | **Owner/build-pipeline + live agent** (platform-agent repo + CI SBOM/SLSA). `agentAttestation` verifier wire'lı (`AttestationVerifier` consumed); evidence **producer** owner-gated. |
| E6 | VIEW_ONLY exfil controls (endpoint DLP/screen-masking, watermark, 'remote-support active' indicator, local-abort, per-session content policy) | 🔒 | **Endpoint-side (platform-agent repo).** Broker tarafında VIEW_ONLY capability-pin ✅ (PILOT_ALLOWED); DLP/masking/watermark agent UI'da, owner-gated. |
| E7 | Endpoint-user coercion UX (visible indicator + always-available local kill + revocable-mid-session consent) | ✅ + 🔒 | ✅ broker tarafı: LOCAL_ABORT → kill (`localAbortAbortsTheLeaseAndKills`) + indicator-loss → kill (`indicatorLossAbortsTheLease...`) + revocable consent lease (clamp/shorten). **🔒:** endpoint UI indicator/kill button (platform-agent). |
| E8 | Broker hardening (separate deployment, NetworkPolicy + per-session egress ACL + namespace isolation, no ambient admin creds, secrets separation) | ✅ + 🔒 | ✅ **gitops `edfb34d8` (feat 22.6/d10-8)**: `endpoint-admin-remote-bridge` ayrı Deployment + SA + ExternalSecret + NetworkPolicy (çift-yön) + egress ACL + namespace isolation scaffold (disabled-by-default). **🔒:** activation overlay apply + secret seed + runtime smoke (operator). |
| E9 | Operator-channel hardening (separate auth, FIDO2/device-posture, ws origin/CSRF, per-channel nonce, no bearer in URL/logs, re-auth/per-action MFA) | ✅ + 🟡 + 🔒 | ✅ FIDO2/WebAuthn step-up (D #598-604, UP/UV/rpIdHash/JCA) + per-action MFA (step-up challenge/verify) + bearer **header'dan, URL'den DEĞİL** (`OperatorCredentialExtractor`) + no-verdict-oracle/redaction + separate operator auth (`OperatorAuthenticator` tenant-scoped, slice-4c). **🟡 GAP:** ws origin/CSRF + per-channel nonce **explicit** kontrol/test. **🔒:** device-posture + live operator authenticator (real IdP/mTLS — slice-4c factory reject-not-yet). |
| E10 | IAM identity canonicalization for dual-control (alias/proxy/service-account resolved before approver≠requester) + approval-fatigue limits | ✅ + 🟡 + 🔒 | ✅ **canonicalization FOUNDATION (#616 `3b2fae16`)**: `CanonicalIdentityResolver` seam + `InMemoryCanonicalIdentityResolver` reference (fail-closed unmapped→empty, no-pass-through; ctor guards normalized-duplicate + non-terminal-canonical-bypass) + `CanonicalIdentityResolverFactory` (IN_MEMORY prod-forbidden, IDP_BACKED reject) + `RemoteSessionAuthz.approverDistinctFromRequesterCanonical` (resolve-both→fail-closed→raw-distinct-on-canonical; alias/SA/proxy self-approval DENIED). **🟡:** approval-fatigue limit + approval-flow **integration** (disabled skeleton henüz consume etmiyor). **🔒:** live IdP-backed resolver producer. |
| E11 | Red-team drill report (broker-compromise sim, jti replay, recorder-down→fail-closed, token theft, NTP skew, key leak/rotation — all pass) | ✅ + 🟡 + 🔒 | ✅ **design-time SIM mostly mapped**: gitops `RB-22-6-remote-bridge-redteam-drill.md` jti-replay / recorder-down / token-theft+cert-binding / clock-skew / oracle+rate-limit test kanıtlarını map'liyor. **🟡:** eksik SIM kalmışsa daralt. **🔒:** live red-team **drill** (insan, çalışan sisteme karşı) + rapor. |

---

## Özet — D10 11/11 statü dağılımı (Codex 019ebe06 accuracy-REVISE absorbed)

- **✅ agent-evidence DONE (tam/parça):** O1, O2a, O2b, **O2d** (raw approver≠requester + **#616 canonicalization foundation**), **O3** (state-machine), E2, E3-part, **E8** (gitops manifest scaffold), **E10-foundation** (#616 `CanonicalIdentityResolver` + canonical dual-control), E7-broker-side, E9-part (FIDO2/MFA/bearer-header/tenant-auth), E4-primitives, **E11-design-time-SIM-mostly** + E1-kill-mekaniği. Bridge suite **271 test** + B2/B1/redteam-runbook + #616 22 test bu kanıtın gövdesi.
- **🟡 agent-completable GAP (daraldı — yazılabilir, owner-gated DEĞİL):** **E10-rest** (approval-fatigue limit + canonical resolver'ı live approval-flow'da consume — foundation ✅, integration kaldı), E9 (ws origin/CSRF + per-channel nonce — küçük net slice), O2c (broker↔jti **integration** neg-test), E4 (integrated operator/broker wire-response **timing/oracle regression**), E1 (heartbeat revoke→kill-**SLO** neg-test), E11 (eksik SIM kalmışsa daralt).
- **🔒 owner-gated (live producer / infra / legal / fiziksel):** O4 (D29-EA live deploy), E3-part (TPM/HSM + CRL/OCSP + trusted-clock), E5 (agent SBOM/SLSA + live attestation producer), E6 (endpoint DLP/masking — platform-agent), E7-part (endpoint UI — platform-agent), E8-part (live activation+secrets+smoke), E9-part (device-posture + live operator IdP/mTLS authenticator), E4-part (live high-rate/load), E11-part (live red-team drill) + **4-rol KVKK pilot kickoff + 2-5 fiziksel IT-owned PC + named roster** (D7).

**Sonuç (düzeltilmiş):** D10 11/11 "hepsi owner-gated" DEĞİL — ama Codex-REVISE ile haritam **fazla-kötümsermiş**: çoğu kriterin agent-evidence'ı zaten kurulu (E8 manifest + O3 state-machine + O2d-raw approver≠requester + E11-SIM-runbook dahil). **Kalan agent-completable gap ~5-6 (daha dar)**; en net + merkezi = **E10 dual-control canonicalization** (raw deny var, eksik olan alias/proxy/SA → canonical + fatigue + integration). Geri kalan owner-gated (live producer/infra/legal/fiziksel).

## Sıradaki agent-completable D10 slice önceliği (güncel — E10 foundation #616 DONE)

1. ✅ **E10 canonicalization foundation** — #616 MERGED (`CanonicalIdentityResolver` + canonical dual-control). Kalan E10-rest: **approval-fatigue limit** (primitive) + canonical resolver'ı live approval-flow'da **consume** (integration — disabled skeleton'ın approval-flow wiring slice'ı ile birlikte).
2. **E9 ws origin/CSRF + per-channel nonce** — NOT: bearer-header REST (cookie-auth değil) büyük ölçüde CSRF-immune; bu gap esas **gelecek WS operator channel** için (mevcut REST controller'a doğrudan uygulanmaz).
3. **O2c/E4/E1 integration neg-testleri** (broker↔jti expired-replay tek-akış + timing/oracle regression + revoke→kill-SLO) — mevcut fail-closed davranışları explicit D10-evidence olarak adlandır.

> NOT: kalan agent-gap'lerin çoğu ya **disabled skeleton'ın approval-flow wiring'ine bağımlı** (E10-integration), ya **gelecek bir kanala ait** (E9-WS), ya da **mostly-covered davranışın explicit-naming'i** (O2c/E4/E1). Yüksek-değerli + merkezi agent-completable D10 işi (map + E10 foundation) DONE; geri kalan dar/incremental.

> Living map: her gap-slice merge oldukça ✅'e çevrilir. Codex notu: özet "DONE" okuması D10-complete izlenimi vermesin — `✅ + 🔒` satırlar agent-evidence tam ama live-producer/pilot hâlâ owner-gated.

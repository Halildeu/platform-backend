# audio-gateway-service

Faz 24 Meeting Intelligence — **Audio Gateway** (Spring Cloud Gateway WebFlux).

> **Status**: Contract 1.0 **REVISION 2026-06-03** — PR-gw-01A normalize (path canonical `/api/v1/audio-gateway` + Idempotency-Key + AudioSessionRegistry + start/status/finish skeleton). PR-gw-01B/C/D/E sonraki slice'larda. "FROZEN" iddiası PR-gw-01 serisi tamamlandıktan sonra.
>
> **ADR-0031 two-server topology**: platform-ai ayrı dedicated host; cross-server WireGuard + mTLS PKI ZORUNLU; Redis bounded queue staging-sw'da (admission/policy Gateway boundary).
>
> **3-AI mutabakat**: Claude + Codex `019e879c` AGREE final + Codex `019e8c26` iter-2 AGREE PR-gw-01A + Mavis `mvs_c922...` msg `78` AGREE.

## Rol

**Tek ingress** Meeting Intelligence için:

```
[Mobile / Web] → audio-gateway-service → (Redis queue / HTTP adapter) → live-stt-service (platform-ai)
```

Mobile/Web hiçbir zaman platform-ai'a doğrudan bağlanmaz (Codex/Mavis RED).

## Scope — PR-gw-01A (this slice, ADR-0031 + Codex `019e8c26` iter-2 AGREE)

- ✅ Maven module skeleton (Spring Boot 3.5.6 + Spring Cloud Gateway 2025.0.1 + Java 21)
- ✅ Path canonical `/api/v1/audio-gateway` (eski `/api/meeting-audio` removed)
- ✅ JWT validation (OAuth2 resource server, Keycloak realm reuse) + fail-closed 401/403
- ✅ Correlation ID propagation (`CorrelationIdWebFilter`)
- ✅ Audio Format whitelist (`WAV / WEBM_OPUS / PCM16` — client allowed subset)
- ✅ Sample rate enum (`16000 / 48000` Hz)
- ✅ Channels guard (mono only PoC)
- ✅ Error model canonical (`ErrorResponse` + namespace codes + IDEMPOTENCY_MISSING/INVALID/CONFLICT + SESSION_NOT_FOUND + SESSION_REGISTRY_FULL)
- ✅ `Idempotency-Key` header canonical (16-128 char opaque, `[A-Za-z0-9._:-]`)
- ✅ `AudioSessionRegistry` interface + bounded in-memory implementation (persistence iddiası YOK — durability PR-gw-01C)
- ✅ Lifecycle endpoints: `POST /sessions` + `GET /sessions/{id}/status` + `POST /sessions/{id}/finish` (idempotent)
- ✅ Tenant/user claim policy: `companyId` + `userId` (configurable); missing → 403 fail-closed; `sub` audit/debug
- ✅ Contract docs revision (`docs/contract-v1.md`)
- ✅ Contract tests (`StartSessionContractTest` + `SessionLifecycleContractTest` — 22 senaryo)

## Slice Roadmap (sonraki PR'lar — Codex `019e8c26` iter-2 AGREE)

| Slice | Scope |
|---|---|
| **PR-gw-01B** | REST chunk admission (`POST /chunks`, 256 KB whitelist, 413/429/503, dispatcher mock) |
| **PR-gw-01C** | Redis Streams producer (bucketed 32-partition + consumer group `live-stt-v1`) |
| **PR-gw-01D / #184** | Authenticated WebSocket audio proxy + live-stt partial/final event relay |

## Direct-STT PCM16 aggregation (#231)

When Direct-STT is enabled, accepted PCM16 recorder chunks keep the existing admission,
idempotency, Redis metadata, and audit contracts, but are no longer forwarded to Whisper
one request per chunk. The gateway holds a bounded, session-scoped, memory-only window and
forwards one canonical WAV per configured window. A first successful session finish flushes
the remaining short tail exactly once.

- Default window: 5 seconds; validated range: 5–30 seconds.
- Default buffered-session bound: 64.
- Raw PCM is never written to Redis or disk and is cleared after window/finish/shutdown.
- A shutdown does not start new STT HTTP work. Any unfinished tail is discarded with
  `aggregation_shutdown_discarded_sessions` / `aggregation_shutdown_discarded_bytes`
  metrics and a WARN log. Rollouts must drain/finish active sessions first.
- Aggregate audit/transcript metadata carries `windowSeq`, first/last chunk sequence,
  window time range, duration, and flush reason; legacy `chunkSeq` remains the last
  chunk sequence for compatibility.
- WAV/WEBM/MP3/M4A/OGG/FLAC retain the existing per-chunk path.
- `max-in-flight` remains a safety bound, not a substitute for aggregation.
- Production acceptance still requires the #231 multi-minute GPU saturation/RTF run.
| **PR-gw-01E** | Contract hardening (client X-* strip code assert + PII guard + invalid transition matrix) |

## Live WebSocket streaming (#184)

The default-off WebSocket endpoint
`/api/v1/audio-gateway/sessions/{sessionId}/stream` proxies a caller-owned PCM16
session to live-stt `/ws/stream`. Desktop clients do not connect directly to
platform-ai.

- JWT `companyId` and `userId` must own the active session.
- Session audio must be PCM16, 16 kHz, mono.
- Each binary frame carries protocol version, contiguous `chunkSeq`, capture
  timestamp, unsigned 16-bit payload length, and little-endian PCM16.
- Replayed sequence numbers are suppressed; gaps are rejected. Sequence state is
  bounded and retained in memory across WebSocket reconnects for the process lifetime.
- Gateway converts PCM16 to live-stt float32 in memory and relays upstream text
  events, including partial/final events, unchanged.
- Cross-host `wss` reuses the Direct-STT client certificate configuration.
- Raw audio and transcript text are not persisted or logged by this bridge.
- The feature remains disabled until the reviewed live-stt and desktop #184
  companion slices are deployed and live acceptance is recorded.

## Non-goals (this slice)

- ❌ REST chunk admission `POST /chunks` (PR-gw-01B)
- ❌ Redis Streams full producer/consumer (PR-gw-01C + PR-queue-01)
- ❌ Header spoof strip code assert + PII guard runtime assert (PR-gw-01E)
- ❌ Real STT inference (PR-stt-02 + PR-stt-03 — cross-server platform-ai dedicated host)
- ❌ Session durability across restart (in-memory only — Redis Streams PR-gw-01C)
- ❌ Meeting access check real impl (PR-gw-02 + meeting-service entegre)
- ❌ Mobile/Web UI (M6)
- ❌ WER / GPU / transcript storage / consent UI

## Build

```bash
# Root'tan (Codex `019e8c26` iter-3 doc fix — module altında wrapper yok)
./mvnw -pl audio-gateway-service test -DfailIfNoTests=false
./mvnw -pl audio-gateway-service spring-boot:run
```

## Endpoints

| Method | Path | Status |
|---|---|---|
| POST | `/api/v1/audio-gateway/sessions` | ✅ **PR-gw-01A LIVE** (start + Idempotency-Key) |
| GET | `/api/v1/audio-gateway/sessions/{id}/status` | ✅ **PR-gw-01A/B-core LIVE** (real chunkCount + lastChunkSeq) |
| POST | `/api/v1/audio-gateway/sessions/{id}/finish` | ✅ **PR-gw-01A LIVE** (idempotent) |
| POST | `/api/v1/audio-gateway/sessions/{id}/chunks` | ✅ **PR-gw-01B-core LIVE** (binary body + X-Audio-* + STREAMING state) |
| WS | `/api/v1/audio-gateway/sessions/{id}/stream` | Implemented, default-off (#184) |

## Config (env override)

| Variable | Default |
|---|---|
| `AUDIO_GATEWAY_JWT_ISSUER_URI` | `http://platform-keycloak:8080/realms/platform` |
| `AUDIO_GATEWAY_DISPATCHER_MODE` | `noop` (PR-gw-01C: `redis`) |
| `AUDIO_GATEWAY_DISPATCHER_QUEUE_FULL_RETRY_AFTER_SECONDS` | `5` |
| `AUDIO_GATEWAY_DISPATCHER_UNAVAILABLE_RETRY_AFTER_SECONDS` | `30` |
| `AUDIO_GATEWAY_BOUNDS_MAX_CHUNK_BYTES` | `262144` (256 KB) — **ADR-0031 update** |
| `AUDIO_GATEWAY_BOUNDS_MAX_BUFFERED_SECONDS` | `30` |
| `AUDIO_GATEWAY_BOUNDS_MAX_SESSION_MINUTES` | `60` — **ADR-0031 update** |
| `AUDIO_GATEWAY_BOUNDS_ADMISSION_QUEUE_CAPACITY` | `1000` |
| `AUDIO_GATEWAY_BOUNDS_MAX_ACTIVE_SESSIONS` | `1000` |
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_ENABLED` | `true` when Direct-STT is enabled |
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_WINDOW_SECONDS` | `5` (validated `5..30`) |
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_MAX_BUFFERED_SESSIONS` | `64` |
| `AUDIO_GATEWAY_DIRECT_STT_STREAMING_ENABLED` | `false` |
| `AUDIO_GATEWAY_DIRECT_STT_STREAM_URL` | empty; required when enabled |
| `AUDIO_GATEWAY_DIRECT_STT_STREAM_MAX_FRAME_BYTES` | `65535` |
| `AUDIO_GATEWAY_DIRECT_STT_STREAM_MAX_TRACKED_SESSIONS` | `1000` |
| `AUDIO_GATEWAY_JWT_TENANT_CLAIM` | `companyId` |
| `AUDIO_GATEWAY_JWT_USER_CLAIM` | `userId` |
| `AUDIO_GATEWAY_IDEMPOTENCY_HEADER_NAME` | `Idempotency-Key` |
| `AUDIO_GATEWAY_IDEMPOTENCY_MIN_LENGTH` | `16` |
| `AUDIO_GATEWAY_IDEMPOTENCY_MAX_LENGTH` | `128` |
| `AUDIO_GATEWAY_IDEMPOTENCY_REPLAY_CACHE_SIZE` | `4096` |

## Contract referans

[docs/contract-v1.md](./docs/contract-v1.md) — REVISION 2026-06-03 (PR-gw-01A normalize).

## Cross-AI Mutabakat

- Codex `019e879c-c51e-7691-8f16-69c781fb787e` (plan-time + iter-3 AGREE)
- Codex `019e8846` (mockJwt + canonical envelope iter-1)
- Codex `019e8c09` (ADR-0031 iter-4 AGREE final)
- Codex `019e8c26-c8ee-7610-8e96-a40bfe62a45a` (PR-gw-01A iter-2 AGREE)
- Mavis `mvs_c922505d66a94a45b031feb3489f9488` msg `74` (PARTIAL) → msg `78` (AGREE) → post-availability non-blocking
- Claude (implementer) AGREE

## References

- Canonical plan: `platform-k8s-gitops/docs/faz-24-meeting-intelligence-plan.md`
- KVKK ADR: `platform-k8s-gitops/docs/adr/0030-kvkk-meeting-intelligence-boundary.md`
- **Two-server topology ADR**: `platform-k8s-gitops/docs/adr/0031-two-server-meeting-intelligence-topology.md`
- Observability: `platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md`
- STT downstream: [`platform-ai/services/live-stt-service`](https://github.com/Halildeu/platform-ai)

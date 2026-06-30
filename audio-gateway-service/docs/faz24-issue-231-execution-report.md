# Faz 24 Issue #231 Execution Report

Issue: `Halildeu/platform-ai#231`

## Objective

Replace the unviable one-Whisper-request-per-100ms-recorder-chunk shape with
bounded, session-scoped, memory-only PCM16 aggregation before the Direct-STT
hop. Keep the existing desktop chunk, gateway admission, idempotency, audit,
and Redis metadata contracts unchanged.

## Requirement Mapping

| Issue acceptance | Implementation | Status |
|---|---|---|
| Aggregate into VAD/utterance or 5-30 second WAV windows | Fixed 10 second PCM16 windows; configurable and validated in the 5-30 second range | Implemented and unit-tested |
| One `/transcribe` request per window | Full windows forward immediately; the first successful session finish flushes one short tail | Implemented and unit-tested |
| Materially lower `dropped_saturation` in a realistic multi-minute recording | Metrics and request shape are ready | Live GPU evidence pending |
| Report elapsed/audio-duration ratio | `audio_gateway_direct_stt_real_time_factor` summary | Implemented; live value pending |
| Prove GPU stability | No code-side concurrency increase; collect RTX 4070 telemetry during the live run | Live evidence pending |
| Benchmark `maxInFlight`, do not use it to hide request-shape deficit | Existing setting remains unchanged | Satisfied by design; tuning pending benchmark |

## Design

- Desktop continues sending its existing 100ms PCM16 chunks.
- Accepted chunks retain their current admission, idempotency, audit, and Redis
  metadata flow.
- Direct-STT keeps raw PCM only in a bounded in-process session buffer.
- A complete window is converted to canonical WAV and sent to live-stt.
- The first successful finish flushes the remaining tail exactly once.
- Replayed finish requests do not flush again.
- Encoded formats keep the pre-existing per-chunk path.
- Raw audio is never written to Redis or disk by the aggregator.
- Internal buffers and completed forwarding payloads are zeroed after use.

Default memory bound for 16 kHz mono PCM16:

`64 sessions * 10 seconds * 32,000 bytes/second = 20,480,000 bytes`

The bound scales with sample rate and channel count. Production telemetry must
therefore include `aggregation_buffered_bytes`.

## Configuration

| Environment variable | Default |
|---|---|
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_ENABLED` | `true` |
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_WINDOW_SECONDS` | `10` |
| `AUDIO_GATEWAY_DIRECT_STT_AGGREGATION_MAX_BUFFERED_SESSIONS` | `64` |

Direct-STT itself remains default-off. These settings take effect only where
Direct-STT is enabled.

## Metrics

- `audio_gateway_direct_stt_aggregation_active_sessions`
- `audio_gateway_direct_stt_aggregation_buffered_bytes`
- `audio_gateway_direct_stt_aggregation_chunks_buffered_total`
- `audio_gateway_direct_stt_aggregation_dropped_capacity_total`
- `audio_gateway_direct_stt_aggregation_error_total`
- `audio_gateway_direct_stt_aggregation_windows_flushed_total{reason=...}`
- `audio_gateway_direct_stt_aggregation_window_bytes`
- `audio_gateway_direct_stt_aggregation_window_duration_ms`
- `audio_gateway_direct_stt_real_time_factor`
- Existing `audio_gateway_direct_stt_dropped_saturation_total`

## Local Verification

Date: 2026-06-30

| Check | Result |
|---|---|
| Targeted aggregation, dispatcher, configuration, wiring, and lifecycle tests | PASS |
| Complete `audio-gateway-service` test suite | 139 tests, 0 failures, 0 errors |
| Java compilation/package | PASS |
| `git diff --check` | PASS |
| 2s + 3s PCM16 input | One 5s WAV request |
| 12 recorder-style 100ms chunks | No per-chunk request; one finish-tail request |
| Repeated finish | Tail forwarded exactly once |
| Session buffer capacity | Additional sessions rejected from Direct-STT aggregation without breaking admission |
| Wrong tenant/user finish | Cannot flush another session's audio |

## Required Live Acceptance Run

This report does not claim issue #231 is complete until the following evidence
is collected from the deployed test environment.

1. Deploy the reviewed backend image with a 10 second window and keep the
   current benchmark `maxInFlight` value unchanged.
2. Record at least three minutes of realistic Turkish speech through the real
   desktop consent/session/chunk/finish path.
3. Capture before/after values for:
   - Direct-STT attempted, success, error, and dropped-saturation counters.
   - Aggregation windows flushed by reason.
   - RTF count and average.
   - Active sessions and buffered bytes.
4. Capture RTX 4070 telemetry during the run:
   - GPU memory used.
   - GPU utilization.
   - OOM, CUDA error, process restart, or inference thrash.
5. Confirm:
   - admitted 100ms chunks greatly exceed `/transcribe` requests;
   - `dropped_saturation` is materially lower than the issue baseline
     (7 drops from 12 admitted chunks);
   - RTF remains below 1.0 at the selected window/concurrency;
   - no OOM, CUDA error, or live-stt restart occurs.
6. Only after this baseline may `maxInFlight` be tuned. Record each tested
   value with RTF, saturation, latency, and GPU memory; do not raise it merely
   to conceal an unsuitable request shape.

## Scope Boundary

- This change implements #231 aggregation, not #184 transcript WebSocket
  delivery/reconnect/missed-buffer semantics.
- It does not change desktop capture behavior.
- It does not persist raw audio.
- It does not claim production readiness or legal approval.
- Provider-independent review and the live GPU acceptance run remain required
  before upstream merge/issue closure.

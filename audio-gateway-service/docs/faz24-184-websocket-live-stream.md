# Faz 24 #184 Gateway WebSocket Execution Report

## Boundary

This change implements the default-off audio-gateway middle layer for
`platform-ai#184`. It does not claim live acceptance, production enablement, or
issue closure.

Canonical client path:

```
desktop -> authenticated audio-gateway WebSocket -> mTLS live-stt /ws/stream
```

Desktop must not connect directly to platform-ai and must not consume Redis.

## Implemented

- Authenticated session-scoped endpoint:
  `/api/v1/audio-gateway/sessions/{sessionId}/stream`
- JWT tenant/user ownership and active-session validation
- PCM16, 16000 Hz, mono session guard
- Versioned bounded binary frame decoder
- Separate durable REST and reconnect-stable live relay sequence state
- First live relay of REST-accepted audio, live duplicate suppression, and
  durable-baseline reconnect jumps
- One active connection per session
- Strict bounded `{"type":"eof"}` terminal control relay
- Bounded drain wait with `eof_ack`/`final`/`drained` client relay
- In-memory PCM16-to-float32 conversion
- Mandatory compute-plane audit before upstream forward
- Direct-STT mTLS reuse for `wss://`
- Backpressure-aware Reactor WebSocket bridge
- Upstream live-stt text event relay
- Connection/frame/upstream-failure metrics
- Default-off environment configuration
- No raw audio or transcript persistence/logging in the bridge

## Source Verification

Executed in the current backend worktree with Java 26:

```text
mvn -Dtest=LiveSttWebSocketProxyHandlerTest,LiveSttWebSocketProxyHandlerLoopbackTest,LiveAudioStreamFrameTest,LiveStreamControlFrameTest,InMemoryAudioSessionRegistryLiveSequenceTest,AudioGatewayPropertiesTest test

mvn -pl audio-gateway-service test
259 tests, 0 failures, 0 errors

git diff --check
PASS
```

The loopback suite covers successful EOF drain, the bounded missing-drain failure
path, and REST-first `seq=0` followed by first/duplicate WebSocket delivery using
real Reactor-Netty WebSocket client/server frames.

## Dependencies

- `platform-ai#233`: reviewed live-stt `/ws/stream` partial/final behavior
- `platform-ai#232`: language override propagation where required
- `platform-desktop#34`: desktop transcript surface and event handling
- Desktop transport follow-up: encode the gateway frame header and connect to
  the audio-gateway session URL, not directly to live-stt
- GitOps: configure reviewed `wss://` URL, enable flag, and existing Direct-STT
  client certificate material

## Live Acceptance Still Required

1. Deploy reviewed immutable images through GitOps.
2. Start an authorized desktop recording session.
3. Stream at least three minutes of Turkish speech through audio-gateway.
4. Verify partial and final events reach the desktop.
5. Disconnect/reconnect and verify replayed sequences do not duplicate output.
6. Apply slow-consumer/backpressure load and verify bounded behavior.
7. Confirm mTLS client-auth, RTX 4070 stability, and no raw-audio persistence.
8. Record correlation/session evidence without audio, transcript, token, or key
   material.

Only after these checks may `platform-ai#184` use closure language.

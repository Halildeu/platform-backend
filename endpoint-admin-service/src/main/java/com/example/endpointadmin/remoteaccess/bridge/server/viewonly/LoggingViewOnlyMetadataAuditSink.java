package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 #1580 — the default {@link ViewOnlyMetadataAuditSink}: a structured metadata log line per observed
 * frame. Deliberately ephemeral (not the durable WORM chain): a recording-OFF bridge persists NO content, and
 * this metadata sink persists no content either — only ids, the payload SIZE, and the disposition. A durable
 * metadata sink (still content-free) can replace this later without giving the recording-off data-plane handler
 * any dependency on a content store.
 */
public final class LoggingViewOnlyMetadataAuditSink implements ViewOnlyMetadataAuditSink {

    private static final Logger log = LoggerFactory.getLogger("remote-bridge.view-only.audit");

    @Override
    public void onFrameObserved(String sessionId,
                                String streamId,
                                long frameSeq,
                                int payloadBytes,
                                String contentType,
                                Disposition disposition,
                                long epochMillis) {
        // metadata only — no payload, ever
        log.info("view-only frame: session={} stream={} seq={} bytes={} type={} disposition={} ts={}",
                sessionId, streamId, frameSeq, payloadBytes, contentType, disposition, epochMillis);
    }
}

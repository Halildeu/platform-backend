package com.example.meeting.service;

import static com.example.common.meeting.bot.BotRecordingContract.*;

/** No worker or user bearer is forwarded to the durable owner. */
public interface BotRecordingTransport {
    Snapshot grant(Grant value);
    Snapshot inspect(IntentRef value);
    Snapshot findRequest(RequestRef value);
    Snapshot bind(Bind value);
    Snapshot revoke(Lookup value);
}

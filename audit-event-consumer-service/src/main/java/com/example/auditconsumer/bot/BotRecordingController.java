package com.example.auditconsumer.bot;

import static com.example.auditconsumer.bot.BotRecordingContract.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Private synchronous commands from meeting-service AFTER its user/scope/directory authorization. */
@RestController
@RequestMapping("/api/v1/internal/bot-recording")
@ConditionalOnProperty(name = "audit.bot-recording.enabled", havingValue = "true")
public class BotRecordingController {
    private final BotRecordingOwner owner;
    public BotRecordingController(BotRecordingOwner owner) { this.owner = owner; }
    @PostMapping("/grant") public ResponseEntity<Snapshot> grant(@RequestBody Grant value) { return ok(owner.grant(value)); }
    @PostMapping("/lookup") public ResponseEntity<Snapshot> lookup(@RequestBody Lookup value) { return ok(owner.lookup(value)); }
    @PostMapping("/bind") public ResponseEntity<Snapshot> bind(@RequestBody Bind value) { return ok(owner.bind(value)); }
    @PostMapping("/revoke") public ResponseEntity<Snapshot> revoke(@RequestBody Lookup value) { return ok(owner.revoke(value)); }
    private static ResponseEntity<Snapshot> ok(Snapshot snapshot) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
    }
}

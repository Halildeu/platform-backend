package com.example.schema.controller;

import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.AiChatService;
import com.example.schema.service.SchemaSnapshotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema/chat")
public class AiChatController {

    private final AiChatService chatService;
    private final SchemaSnapshotService snapshotService;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    public AiChatController(AiChatService chatService, SchemaSnapshotService snapshotService) {
        this.chatService = chatService;
        this.snapshotService = snapshotService;
    }

    @PostMapping
    public ResponseEntity<AiChatService.ChatResponse> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        String schema = body.getOrDefault("schema", defaultSchema);

        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(
                new AiChatService.ChatResponse("Message cannot be empty", null, java.util.List.of(), false)
            );
        }

        SchemaSnapshot snapshot = snapshotService.buildSnapshot(schema);
        return ResponseEntity.ok(chatService.chat(message, snapshot));
    }
}

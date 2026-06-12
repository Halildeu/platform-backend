package com.example.endpointadmin.remoteaccess.bridge.contract;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Faz 22.6 T-1a — the canonical form of a constrained-PTY command, and its stable hash. The broker signs an
 * {@code OperationPermit} over the command HASH (not the raw string); the agent verifies the permit and runs
 * exactly the canonical {@code commandId + argv} (the D-2 no-shell executor invariant) — it never re-parses a
 * free string into a shell. Tokenised identically to the D-2/D-3 gates (trim, split on one-or-more spaces) so
 * the policy engine and the permit agree on what "the command" is.
 *
 * <p><b>Hash discipline (Codex 019eb9fb):</b> the hash is over a length-prefixed canonical (4-byte BE length +
 * UTF-8 per field) of the lowercased {@code commandId} then each {@code argv} token IN ORDER, under a domain
 * tag — so re-ordering or changing any argument yields a different hash (no `a b` ≡ `b a`, no `ab` ≡ `a b`).
 * Total: a null/blank line canonicalises to the empty command (commandId {@code ""}, no argv) — which the
 * broker treats as "no command", denying a CONSTRAINED_PTY operation that lacks one.
 */
public record CanonicalCommand(String commandId, List<String> argv) {

    private static final String DOMAIN = "RemoteBridgeCommand:v1";

    /**
     * The compact constructor ENFORCES the canonical invariant (Codex 019eb9fb) so a direct construction is
     * indistinguishable from {@link #of(String)}: {@code commandId} is trimmed + lowercased (blank → {@code ""});
     * {@code argv} is an immutable copy with NO null elements ({@link List#copyOf} rejects nulls — a malformed
     * wire command fails CLOSED at construction, never later in {@link #hash()}).
     */
    public CanonicalCommand {
        commandId = commandId == null || commandId.isBlank() ? "" : commandId.trim().toLowerCase(Locale.ROOT);
        argv = argv == null ? List.of() : List.copyOf(argv);
    }

    /** Canonicalise a raw command line exactly as the D-2/D-3 gates tokenise it. Never throws. */
    public static CanonicalCommand of(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return new CanonicalCommand("", List.of());
        }
        String[] tokens = commandLine.trim().split(" +");
        // the constructor lowercases commandId — pass the raw token, normalisation is centralised there
        return new CanonicalCommand(tokens[0], List.of(tokens).subList(1, tokens.length));
    }

    /** True when there is no command (the empty canonicalisation) — the broker denies a PTY op without one. */
    public boolean isEmpty() {
        return commandId.isEmpty();
    }

    /** SHA-256 hex over the length-prefixed canonical (domain-separated, delimiter-safe). Stable + order-sensitive. */
    public String hash() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeField(dos, DOMAIN);
            writeField(dos, commandId);
            for (String arg : argv) {
                writeField(dos, arg);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeField(DataOutputStream dos, String field) throws IOException {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }
}

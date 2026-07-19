package com.example.endpointadmin.remoteaccess.preflight;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Vault Agent/ESO file-sink reader; reads every request so rotation is observed. */
public final class FileViewOnlyVaultTokenSource implements ViewOnlyVaultTokenSource {
    private static final int MAX_TOKEN_FILE_BYTES = 8_192;

    private final Path tokenFile;

    public FileViewOnlyVaultTokenSource(Path tokenFile) {
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile").toAbsolutePath().normalize();
    }

    @Override
    public char[] readToken() {
        byte[] bytes = null;
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    tokenFile, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel)) {
                bytes = input.readNBytes(MAX_TOKEN_FILE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_TOKEN_FILE_BYTES) {
                throw unavailable("Vault token sink is absent or outside its hard size bound", null);
            }
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String token = decoded.toString().strip();
            if (token.isEmpty() || token.length() > 4_096 || token.chars().anyMatch(Character::isWhitespace)) {
                throw unavailable("Vault token sink content is invalid", null);
            }
            return token.toCharArray();
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (Exception failure) {
            throw unavailable("Vault token sink could not be read", failure);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static ViewOnlyAuthorityException unavailable(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE, message, cause);
    }
}

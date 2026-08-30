package com.example.endpointadmin.remoteaccess.preflight;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Bounded regular-file reader that supports Kubernetes projected-volume
 * rotation without accepting a symlink escape from the configured mount.
 *
 * <p>The configured key path may be the standard ConfigMap/Secret symlink. It
 * is resolved once to the immutable timestamped projection target, required to
 * remain below the real configured parent directory, and then opened with
 * {@link LinkOption#NOFOLLOW_LINKS}. A later {@code ..data} rotation is seen by
 * the next read while the current read keeps one stable target.</p>
 */
final class ViewOnlySecureProjectedFileReader {
    private ViewOnlySecureProjectedFileReader() {
    }

    static byte[] read(Path configuredPath, int maximumBytes) throws IOException {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        Path configured = configuredPath.toAbsolutePath().normalize();
        Path parent = configured.getParent();
        if (parent == null) {
            throw new IOException("configured file has no parent directory");
        }
        // Canonicalize intermediate platform links (for example macOS /var ->
        // /private/var) before comparing the resolved projected target.  The
        // final configured key may still be a Kubernetes projection symlink;
        // confinement is enforced against this canonical mount directory.
        Path realParent = parent.toRealPath();
        Path resolved = configured.toRealPath();
        if (!resolved.startsWith(realParent)
                || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(resolved)) {
            throw new IOException("projected file target is not a confined regular file");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(
                resolved, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            return input.readNBytes(maximumBytes + 1);
        }
    }
}

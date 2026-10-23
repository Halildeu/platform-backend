package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewOnlySecureProjectedFileReaderTest {
    @TempDir
    Path directory;

    @Test
    void resolvesEachConfinedProjectionToOneImmutableRegularTarget() throws Exception {
        Path first = Files.createDirectory(directory.resolve("..2026_07_19_01"));
        Path second = Files.createDirectory(directory.resolve("..2026_07_19_02"));
        Files.writeString(first.resolve("root.json"), "first", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("root.json"), "second", StandardCharsets.UTF_8);
        Path data = directory.resolve("..data");
        Files.createSymbolicLink(data, first.getFileName());
        Path projected = directory.resolve("root.json");
        Files.createSymbolicLink(projected, Path.of("..data", "root.json"));

        assertThat(ViewOnlySecureProjectedFileReader.read(projected, 32))
                .isEqualTo("first".getBytes(StandardCharsets.UTF_8));

        Files.delete(data);
        Files.createSymbolicLink(data, second.getFileName());
        assertThat(ViewOnlySecureProjectedFileReader.read(projected, 32))
                .isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsProjectionEscapesAndOversizedFiles() throws Exception {
        Path outside = Files.createTempFile("view-only-outside-", ".json");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Path escaped = directory.resolve("escaped.json");
        Files.createSymbolicLink(escaped, outside);

        assertThatThrownBy(() -> ViewOnlySecureProjectedFileReader.read(escaped, 32))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("confined regular file");

        Path oversized = directory.resolve("oversized.json");
        Files.write(oversized, new byte[33]);
        assertThat(ViewOnlySecureProjectedFileReader.read(oversized, 32)).hasSize(33);
    }
}

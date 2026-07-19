package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileViewOnlyVaultTokenSourceTest {

    @TempDir
    Path directory;

    @Test
    void readsRotatableFileSinkWithoutPersistingNewline() throws Exception {
        Path sink = directory.resolve("token");
        Files.writeString(sink, "hvs.test-token\n");
        FileViewOnlyVaultTokenSource source = new FileViewOnlyVaultTokenSource(sink);

        assertThat(source.readToken()).containsExactly("hvs.test-token".toCharArray());

        Files.writeString(sink, "hvs.rotated-token\n");
        assertThat(source.readToken()).containsExactly("hvs.rotated-token".toCharArray());
    }

    @Test
    void rejectsMissingOrWhitespaceBearingCredentialAsSigningFailure() throws Exception {
        Path sink = directory.resolve("token");
        FileViewOnlyVaultTokenSource source = new FileViewOnlyVaultTokenSource(sink);
        assertThatThrownBy(source::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);

        Files.writeString(sink, "hvs.not valid\n");
        assertThatThrownBy(source::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessage("Vault token sink content is invalid");
    }

    @Test
    void rejectsOversizedAndEscapingSymlinkedTokenSinks() throws Exception {
        Path oversized = directory.resolve("oversized-token");
        Files.write(oversized, new byte[8_193]);
        assertThatThrownBy(new FileViewOnlyVaultTokenSource(oversized)::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("hard size bound");

        Path outside = Files.createTempDirectory("view-only-outside-");
        Path target = outside.resolve("real-token");
        Files.writeString(target, "hvs.real-token\n");
        Path symlink = directory.resolve("token-link");
        Files.createSymbolicLink(symlink, target);
        assertThatThrownBy(new FileViewOnlyVaultTokenSource(symlink)::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessage("Vault token sink could not be read");
    }

    @Test
    void followsConfinedProjectedVolumeRotationWithoutFollowingTheFinalTarget() throws Exception {
        Path versionOne = Files.createDirectory(directory.resolve("..2026_07_19_01"));
        Path versionTwo = Files.createDirectory(directory.resolve("..2026_07_19_02"));
        Files.writeString(versionOne.resolve("token"), "hvs.version-one\n");
        Files.writeString(versionTwo.resolve("token"), "hvs.version-two\n");
        Path data = directory.resolve("..data");
        Files.createSymbolicLink(data, versionOne.getFileName());
        Path projected = directory.resolve("token");
        Files.createSymbolicLink(projected, Path.of("..data", "token"));
        FileViewOnlyVaultTokenSource source = new FileViewOnlyVaultTokenSource(projected);

        assertThat(source.readToken()).containsExactly("hvs.version-one".toCharArray());

        Files.delete(data);
        Files.createSymbolicLink(data, versionTwo.getFileName());
        assertThat(source.readToken()).containsExactly("hvs.version-two".toCharArray());
    }
}

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
    void rejectsOversizedAndSymlinkedTokenSinks() throws Exception {
        Path oversized = directory.resolve("oversized-token");
        Files.write(oversized, new byte[8_193]);
        assertThatThrownBy(new FileViewOnlyVaultTokenSource(oversized)::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("hard size bound");

        Path target = directory.resolve("real-token");
        Files.writeString(target, "hvs.real-token\n");
        Path symlink = directory.resolve("token-link");
        Files.createSymbolicLink(symlink, target);
        assertThatThrownBy(new FileViewOnlyVaultTokenSource(symlink)::readToken)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessage("Vault token sink could not be read");
    }
}

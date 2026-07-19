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
}

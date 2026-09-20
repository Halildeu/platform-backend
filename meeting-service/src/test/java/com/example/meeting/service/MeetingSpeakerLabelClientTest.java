package com.example.meeting.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.meeting.config.MeetingTranscriptReadProperties;
import com.example.common.meeting.speakers.SpeakerLabels;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;

class MeetingSpeakerLabelClientTest {
    @Test void defaultOffAndPerPermissionTokenCache() {
        var properties = new MeetingTranscriptReadProperties(); properties.setEnabled(true); properties.setClientSecret("test-only");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = new MeetingTranscriptReadTokenProvider(properties, builder.build(), Clock.systemUTC());
        assertThatThrownBy(() -> tokens.speakerLabelToken(false)).isInstanceOf(IllegalStateException.class);
        properties.setSpeakerLabelsEnabled(true);
        for (String permission : new String[] {"canonical:read", "speaker-label:read", "speaker-label:write"}) {
            server.expect(requestTo(properties.getTokenUrl())).andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "permissions=transcript%3A" + permission.replace(":", "%3A"))))
                    .andRespond(withSuccess("{\"access_token\":\"" + permission + "\",\"expires_in\":60}", MediaType.APPLICATION_JSON));
        }
        assertThat(tokens.token()).isEqualTo("canonical:read");
        assertThat(tokens.speakerLabelToken(false)).isEqualTo("speaker-label:read");
        assertThat(tokens.speakerLabelToken(true)).isEqualTo("speaker-label:write");
        assertThat(tokens.token()).isEqualTo("canonical:read");
        assertThat(tokens.speakerLabelToken(false)).isEqualTo("speaker-label:read"); server.verify();
    }
    @Test void writeUsesDedicatedPermissionAndNeverRetriesConflictsOrLostResponse() {
        var properties = new MeetingTranscriptReadProperties(); properties.setEnabled(true); properties.setSpeakerLabelsEnabled(true);
        var tokens = mock(MeetingTranscriptReadTokenProvider.class); when(tokens.speakerLabelToken(true)).thenReturn("write-only");
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpCanonicalTranscriptClient(properties, tokens, builder.build());
        UUID id = UUID.randomUUID(); var edit = new SpeakerLabels.Edit(id, "S1", "A", 0);
        server.expect(method(HttpMethod.PUT)).andExpect(header("Authorization", "Bearer write-only"))
                .andExpect(header("X-Actor-Subject", "stable-owner")).andExpect(content().json(
                        "{\"scope\":\"" + id + "\",\"speaker\":\"S1\",\"name\":\"A\",\"expectedRevision\":0}"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> client.speakerLabels(id, id, id, 1, id, "spec", "stable-owner", edit))
                .isInstanceOfSatisfying(CanonicalTranscriptClient.SpeakerLabelFailure.class, ex -> assertThat(ex.status()).isEqualTo(409));
        server.verify(); verify(tokens, never()).token(); verify(tokens, never()).invalidate();
    }
}

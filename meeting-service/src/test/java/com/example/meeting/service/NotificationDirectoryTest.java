package com.example.meeting.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotificationDirectoryTest {
    MockRestServiceServer server;
    HttpAssigneeDirectoryClient directory;
    @BeforeEach void setup() {
        var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
        var p=new MeetingAssigneeDirectoryProperties(); p.setEnabled(true); p.setUserServiceBaseUrl("http://users");
        var tokens=mock(AssigneeDirectoryTokenProvider.class); when(tokens.token()).thenReturn("token");
        directory=new HttpAssigneeDirectoryClient(p,tokens,builder.build());
    }
    void resolved(String json) {
        server.expect(requestTo("http://users/api/users/internal/authenticated-principal/resolve"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
    }
    @Test void preservesAccountAndCompanyForEligibility() {
        resolved("{\"userId\":7,\"subjectMatched\":true,\"enabled\":false,\"deleted\":true,\"companyId\":9}");
        assertThat(directory.resolveNotificationIdentity("issuer","stable")).contains(new AssigneeDirectoryClient.NotificationIdentity(7,false,true,9L));
        server.verify();
    }
    @Test void rejectsEmailFallbackIdentity() {
        resolved("{\"userId\":7,\"subjectMatched\":false,\"enabled\":true,\"deleted\":false,\"companyId\":9}");
        assertThat(directory.resolveNotificationIdentity("issuer","stable")).isEmpty(); server.verify();
    }
    @Test void numericAliasMustRoundTripToSameId() {
        server.expect(requestTo("http://users/api/users/internal/7/impersonation-target"))
            .andRespond(withSuccess("{\"id\":7,\"enabled\":true,\"kcSubject\":\"stable\"}",MediaType.APPLICATION_JSON));
        resolved("{\"userId\":8,\"subjectMatched\":true,\"enabled\":true,\"deleted\":false,\"companyId\":9}");
        assertThat(directory.resolveNotificationIdentity("issuer","7")).isEmpty(); server.verify();
    }
}

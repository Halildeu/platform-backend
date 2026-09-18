package com.example.meeting.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.exception.GlobalExceptionHandler;
import com.example.meeting.notify.MeetingReadyRecipients;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=MeetingNotificationRecipientsController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MeetingNotificationRecipientsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MeetingReadyRecipients recipients;
    @MockitoBean OpenFgaAuthzService authz;
    final UUID meeting=UUID.randomUUID(), org=UUID.randomUUID();
    final String path="/api/v1/internal/meetings/{id}/notification-recipients";
    String body() { return "{\"tenantId\":\""+org+"\",\"orgId\":\""+org+"\"}"; }
    @Test void exactPermissionReturnsOnlyIds() throws Exception {
        when(recipients.resolve(meeting, org, org)).thenReturn(List.of(7L));
        mvc.perform(post(path,meeting).with(jwt().authorities(new SimpleGrantedAuthority("SVC_meeting:notification:read")))
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(content().json("[7]"));
    }
    @ParameterizedTest
    @ValueSource(strings={"ROLE_ADMIN","SVC_meeting:session:resolve","SVC_meeting:analysis-result:write","PERM_meeting:notification:read"})
    void otherPermissionsCannotEnumerate(String authority) throws Exception {
        mvc.perform(post(path,meeting).with(jwt().authorities(new SimpleGrantedAuthority(authority)))
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(recipients);
    }
    @Test void anonymousCannotEnumerate() throws Exception {
        mvc.perform(post(path,meeting).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(recipients);
    }
}

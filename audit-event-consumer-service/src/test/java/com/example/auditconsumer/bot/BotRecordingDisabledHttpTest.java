package com.example.auditconsumer.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BotRecordingDisabledHttpTest.Application.class, properties = {
        "audit.bot-recording.enabled=false", "eureka.client.enabled=false", "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class BotRecordingDisabledHttpTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @Import({BotRecordingSecurity.class, BotRecordingController.class})
    static class Application {}
    @Autowired MockMvc mvc;
    @MockitoBean BotRecordingOwner owner;
    @Test void disabledCommandsAreDeniedEvenWithOtherwiseValidToken() throws Exception {
        mvc.perform(post("/api/v1/internal/bot-recording/grant").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/bot-recording/grant").contentType("application/json").content("{}")
                        .header("Authorization", "Bearer " + BotRecordingSecurityTest.token(b -> {})))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(owner);
    }
    @Test void existingHealthAndInfoRemainPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        verifyNoInteractions(owner);
    }
}

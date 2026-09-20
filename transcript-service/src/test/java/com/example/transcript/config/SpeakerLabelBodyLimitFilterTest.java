package com.example.transcript.config;
import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
class SpeakerLabelBodyLimitFilterTest {
    @Test void chunkedBodyIsBoundedBeforeParsingAndSmallBodyIsPreserved() throws Exception {
        var filter = new SpeakerLabelBodyLimitFilter();
        var request = new MockHttpServletRequest("PUT", "/api/v1/x/speaker-labels") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent("x".repeat(4097).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter.doFilter(request, response, (a,b) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(called.get()).isFalse();
        request.setContent("{\"name\":\"Zeynep\"}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request, new MockHttpServletResponse(), (a,b) -> {
            assertThat(new String(a.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"Zeynep\"}");
            called.set(true);
        });
        assertThat(called.get()).isTrue();
    }
}

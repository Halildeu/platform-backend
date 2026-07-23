package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.api.EthicsDtos.CreateReportRequest;
import com.example.ethics.api.EthicsDtos.MessageRequest;
import com.example.ethics.api.EthicsDtos.ReportCategory;
import com.example.ethics.api.EthicsDtos.ReportMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicIntakeSanitizerTest {
    private final PublicIntakeSanitizer sanitizer = new PublicIntakeSanitizer();

    private static CreateReportRequest reportWith(String subject, String description) {
        return new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                subject,
                description,
                "tr",
                "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG_-",
                "notice-1");
    }

    @Test
    void plainReportPasses() {
        assertThatCode(() -> sanitizer.validateReport(
                reportWith("Konu", "Bir olay yaşandı. Detaylar sonradan iletilecek."))).doesNotThrowAnyException();
    }

    @Test
    void mathematicalLessThanCharacterPasses() {
        assertThatCode(() -> sanitizer.validateReport(
                reportWith("x < 5", "Değer 3 <= a <= 7 aralığında."))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<iframe src='javascript:alert(1)'></iframe>",
            "< script >alert(1)< /script >",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "&#60;script&#62;alert(1)&#60;/script&#62;",
            "%3Cscript%3Ealert(1)%3C/script%3E",
            "<svg/onload=alert(1)>",
            "<a href='javascript:alert(1)'>x</a>",
    })
    void reportRejectsHtmlTagPayloads(String payload) {
        assertThatThrownBy(() -> sanitizer.validateReport(reportWith("Konu", payload)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).isEqualTo("INPUT_HTML_NOT_ALLOWED");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "https://169.254.169.254/computeMetadata",
            "http://127.0.0.1:8080/admin",
            "http://localhost:9000/internal",
            "http://10.0.0.5/private",
            "http://192.168.1.1/router",
            "http://172.16.5.9/kube-api",
            "http://172.31.0.1/vpc",
            "file:///etc/passwd",
            "gopher://127.0.0.1:6379/x_",
            "jndi://127.0.0.1/exploit",
            "http://[::1]/loopback",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://metadata.azure.internal/metadata/",
            "http://instance-data/latest/meta-data/",
    })
    void reportRejectsBlockedUrlPayloads(String payload) {
        assertThatThrownBy(() -> sanitizer.validateReport(reportWith("Konu", "Bkz: " + payload)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).isEqualTo("INPUT_URL_BLOCKED");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/case/123",
            "https://acme.co.uk/incidents",
            "http://public-jira.acme.com/browse/PROJ-42",
            "https://8.8.8.8/dns",
    })
    void reportAllowsLegitimateExternalUrls(String url) {
        assertThatCode(() -> sanitizer.validateReport(reportWith("Konu", "Referans: " + url)))
                .doesNotThrowAnyException();
    }

    @Test
    void subjectAndDescriptionAreBothScanned() {
        assertThatThrownBy(() -> sanitizer.validateReport(
                reportWith("<script>x</script>", "Sade metin")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("INPUT_HTML_NOT_ALLOWED"));
        assertThatThrownBy(() -> sanitizer.validateReport(
                reportWith("Sade konu", "http://169.254.169.254/")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("INPUT_URL_BLOCKED"));
    }

    @Test
    void reporterReplyRejectsHtmlPayload() {
        assertThatThrownBy(() -> sanitizer.validateMessage(new MessageRequest("<img src=x onerror=alert(1)>")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("INPUT_HTML_NOT_ALLOWED"));
    }

    @Test
    void reporterReplyRejectsMetadataUrl() {
        assertThatThrownBy(() -> sanitizer.validateMessage(new MessageRequest("bkz http://169.254.169.254/")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("INPUT_URL_BLOCKED"));
    }

    @Test
    void reporterReplyPassesPlainText() {
        assertThatCode(() -> sanitizer.validateMessage(new MessageRequest("Merhaba, ek belge iletiyorum."))).doesNotThrowAnyException();
    }
}

package com.serban.notify.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.NotificationTemplateRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * ES-301b (#1153): the V29 seed, read back through the migrated schema and rendered by the
 * real renderer. A `${vars.level}` written as plain text would ship unrendered; this pins
 * that each locale renders the integer level and leaves no placeholder behind, and that the
 * copy names no case.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class EthicsEscalationTemplateSeedTest extends AbstractPostgresTest {

    @Autowired NotificationTemplateRepository templates;
    @Autowired TemplateRenderer renderer;

    @Test
    @DisplayName("ethics.case.escalated v1 tr-TR/en-US: seviye render edilir, placeholder kalmaz, vaka adı geçmez")
    void seededEscalationTemplatesRenderTheLevel() {
        for (String locale : List.of("tr-TR", "en-US")) {
            NotificationTemplate template = templates.findByTemplateIdAndVersionAndLocale("ethics.case.escalated", 1, locale)
                    .orElseThrow(() -> new AssertionError("V29 seed missing for " + locale));
            assertThat(template.isExternalAllowed()).as("%s external_allowed", locale).isFalse();
            assertThat(template.isActive()).isTrue();
            for (int level : List.of(1, 2, 5)) {
                RenderedMessage out = renderer.render(template, Map.of("level", level));
                for (String part : List.of(out.subject(), out.bodyText(), out.bodyHtml())) {
                    assertThat(part).as("%s L%d", locale, level)
                            .contains(String.valueOf(level))
                            .doesNotContain("${").doesNotContain("[[").doesNotContain("vars.level")
                            .doesNotContain("case-").doesNotContain("caseId").doesNotContain("receipt");
                }
            }
        }
    }

    @Test
    @DisplayName("etkinlik şablonu (V24) değişmedi: aynı kimlik, sabit kopya")
    void theActivityTemplateIsUntouched() {
        NotificationTemplate activity = templates.findByTemplateIdAndVersionAndLocale("ethics.case.activity", 1, "tr-TR")
                .orElseThrow();
        assertThat(renderer.render(activity, Map.of()).subject()).isEqualTo("Etik bildirim kuyruğunda yeni işlem");
    }
}

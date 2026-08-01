package com.example.kcsmsotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Every channel must carry its own wording (gitops#3251).
 *
 * <p>Both lanes render one template, so the strings that name <em>where the
 * code went</em> cannot be shared. On 2026-08-01 the e-mail challenge screen
 * was measured live titled "SMS verification" and telling the user a code had
 * gone to a phone that was never involved — the delivery worked, the screen
 * lied. That is the failure this pins: adding a channel without adding its two
 * strings fails here rather than on a stranger's login screen.
 */
class ChannelMessagesTest {

    private static final List<String> CHANNELS =
            List.of(SmsOtpConfig.CHANNEL_SMS, SmsOtpConfig.CHANNEL_EMAIL);
    private static final List<String> LOCALES = List.of("en", "tr");

    private Properties bundle(String locale) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("theme-resources/messages/messages_" + locale + ".properties")) {
            assertThat(in).as("bundle for %s", locale).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyChannelHasATitleAndAnInstructionInEveryLocale() throws Exception {
        for (String locale : LOCALES) {
            Properties p = bundle(locale);
            for (String channel : CHANNELS) {
                assertThat(p.getProperty(channel + "OtpFormTitle"))
                        .as("%s: %sOtpFormTitle", locale, channel)
                        .isNotNull().isNotBlank();
                assertThat(p.getProperty(channel + "OtpInstruction"))
                        .as("%s: %sOtpInstruction", locale, channel)
                        .isNotNull().isNotBlank();
            }
        }
    }

    @Test
    void everyInstructionCarriesTheRecipientPlaceholder() throws Exception {
        // Without {0} the screen would not say where the code went at all —
        // silently worse than saying the wrong thing.
        for (String locale : LOCALES) {
            Properties p = bundle(locale);
            for (String channel : CHANNELS) {
                assertThat(p.getProperty(channel + "OtpInstruction"))
                        .as("%s: %sOtpInstruction placeholder", locale, channel)
                        .contains("{0}");
            }
        }
    }

    @Test
    void theTwoChannelsDoNotShareOneWording() throws Exception {
        // The whole point: if these ever collapse to the same string, one of the
        // two screens is lying about the channel again.
        for (String locale : LOCALES) {
            Properties p = bundle(locale);
            assertThat(p.getProperty("smsOtpFormTitle"))
                    .as("%s: sms and e-mail titles must differ", locale)
                    .isNotEqualTo(p.getProperty("emailOtpFormTitle"));
            assertThat(p.getProperty("smsOtpInstruction"))
                    .as("%s: sms and e-mail instructions must differ", locale)
                    .isNotEqualTo(p.getProperty("emailOtpInstruction"));
        }
    }

    @Test
    void theTemplateResolvesBothStringsThroughTheChannelVariable() throws Exception {
        // A bundle full of correct strings proves nothing if the template still
        // hardcodes one channel's key — which is exactly how this shipped.
        String ftl;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("theme-resources/templates/sms-otp-form.ftl")) {
            assertThat(in).isNotNull();
            ftl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(ftl).contains("otpChannel + \"OtpFormTitle\"");
        assertThat(ftl).contains("otpChannel + \"OtpInstruction\"");
        assertThat(ftl).doesNotContain("msg(\"smsOtpFormTitle\")");
        assertThat(ftl).doesNotContain("msg(\"smsOtpInstruction\"");
        // The recipient is not a phone on the e-mail lane.
        assertThat(ftl).doesNotContain("maskedPhone");
    }
}

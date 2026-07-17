package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.NoOpAudioGatewayAuditSink;
import com.example.audiogateway.service.RedisStreamAuditSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

class AuditRedisConfigurationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void canonicalAudioGatewayPrefixBindsAndActivatesRedisSink() {
        runner.withPropertyValues(
                        "audio.gateway.audit.redis.enabled=true",
                        "audio.gateway.audit.redis.stream-key=audit:canonical",
                        "audio.gateway.audit.redis.max-len=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisStreamAuditSink.class);
                    assertThat(context.getBean(AudioGatewayAuditSink.class))
                            .isInstanceOf(RedisStreamAuditSink.class);
                    assertThat(context.getBean(AudioGatewayProperties.class)
                            .getAudit().getRedis().getStreamKey()).isEqualTo("audit:canonical");
                });
    }

    @Test
    void retiredPrefixDoesNotActivateRedisSink() {
        runner.withPropertyValues("audiogateway.audit.redis.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisStreamAuditSink.class);
                    assertThat(context.getBean(AudioGatewayAuditSink.class))
                            .isInstanceOf(NoOpAudioGatewayAuditSink.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AudioGatewayProperties.class)
    @Import({RedisStreamAuditSink.class, NoOpAudioGatewayAuditSink.class})
    static class TestConfiguration {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}

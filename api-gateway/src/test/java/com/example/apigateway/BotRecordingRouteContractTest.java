package com.example.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class BotRecordingRouteContractTest {
    @ParameterizedTest @ValueSource(strings = {"application.properties", "application-k8s.yml"})
    void publicBotRoutesReachMeetingServiceWithoutExposingInternalOrChangingOtherMeetingPaths(String config) throws Exception {
        var file = new FileSystemResource(Path.of("src/main/resources", config));
        var sources = config.endsWith(".yml") ? new YamlPropertySourceLoader().load("routes", file)
                : new PropertiesPropertySourceLoader().load("routes", file);
        var environment = new StandardEnvironment();
        // Production loads base first, then profile overrides. Lists replace, never merge.
        var baseSources = new PropertiesPropertySourceLoader().load("base", new FileSystemResource(Path.of("src/main/resources/application.properties")));
        baseSources.forEach(source -> environment.getPropertySources().addFirst(source));
        List<RouteDefinition> original = Binder.get(environment).bind("spring.cloud.gateway.server.webflux.routes",
                org.springframework.boot.context.properties.bind.Bindable.listOf(RouteDefinition.class)).get();
        sources.forEach(source -> environment.getPropertySources().addFirst(source));
        List<RouteDefinition> routes = Binder.get(environment).bind("spring.cloud.gateway.server.webflux.routes",
                org.springframework.boot.context.properties.bind.Bindable.listOf(RouteDefinition.class)).get();
        // A bot route must not remove/change any base auth, user, audit, reports, theme, or mobile routing.
        assertThat(routes).usingRecursiveComparison().isEqualTo(original);
        var meeting = routes.stream().filter(route -> route.getId().startsWith("meeting-service-admin")).findFirst().orElseThrow();
        assertThat(meeting.getFilters()).isEmpty(); assertThat(meeting.getUri().toString().toLowerCase(java.util.Locale.ROOT)).contains("meeting-service");
        var definition = meeting.getPredicates().stream().filter(p -> p.getName().equals("Path")).findFirst().orElseThrow();
        var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config().setPatterns(List.copyOf(definition.getArgs().values())));
        String base = "/api/v1/meetings/" + UUID.randomUUID() + "/bot-recording-intents";
        for (String suffix : List.of("", "/terms", "/" + UUID.randomUUID())) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE))
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.method(method, base + suffix)))).isTrue();
        }
        for (String path : List.of("/api/v1/internal/meetings/1/bot-recording/admit", "/api/v1/meetings/1/recording",
                "/api/v1/meetings/1/sessions", "/api/v1/meetings/1/other/bot-recording-intents"))
            assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get(path)))).isFalse();
        assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/meetings/1")))).isTrue();
    }
}

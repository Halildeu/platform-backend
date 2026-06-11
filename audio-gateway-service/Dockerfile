# audio-gateway-service — Faz 24 Meeting Intelligence (PR-gw-01 family)
# Root-context pattern (align with api-gateway/notification-orchestrator):
# ci-image-push.yml builds with context=. file=audio-gateway-service/Dockerfile

# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# pom first — dependency layer cache (no code change → no re-download)
# (no `|| true` masking — prefetch failure should surface here, not in package)
COPY audio-gateway-service/pom.xml .
RUN mvn dependency:go-offline -q

COPY audio-gateway-service/src ./src
RUN mvn clean package -DskipTests -q

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-jammy

# D14: non-root user
RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar

# 8210 = server.port; 8081 = k8s profilinde management/actuator portu
# (application-k8s.yml management.server.port — drift gate spring-actuator
# contract'ı; base profilde actuator 8210'da kalır)
EXPOSE 8210 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

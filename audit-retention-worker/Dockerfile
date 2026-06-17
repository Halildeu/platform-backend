## Build Stage
# Faz 24 KVKK audit pipeline (gitops#1250) — audit-retention-worker.
# Single-module build (no common-auth: the worker has no auth/HTTP surface).
# Build context is the repo root (ci-image-push / etl-worker pattern).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY audit-retention-worker/pom.xml ./audit-retention-worker/pom.xml
COPY audit-retention-worker/src ./audit-retention-worker/src

RUN mvn -f audit-retention-worker/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/audit-retention-worker/target/*.jar app.jar
RUN chown app:app app.jar

# 8101 = app (run-once mode exits before serving); 8081 = management/actuator
# (drift-gate probe contract for Deployment/scheduler mode).
EXPOSE 8101 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

## Build Stage
# Faz 24 KVKK audit pipeline (gitops#1249) — multi-module build:
# shared local modules + audit-event-consumer-service.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# common-auth dependency için pom + src (Maven Central'da yok — local repo cache)
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src

# common-meeting-events dependency (shared Faz 24 event contract)
COPY common-meeting-events/pom.xml ./common-meeting-events/pom.xml
COPY common-meeting-events/src ./common-meeting-events/src

# audit-event-consumer-service pom + src
COPY audit-event-consumer-service/pom.xml ./audit-event-consumer-service/pom.xml
COPY audit-event-consumer-service/src ./audit-event-consumer-service/src

# Yerel ortak modulleri once install et (Maven Central'da yok; temiz Docker
# build icin zorunlu).
RUN mvn -f common-auth/pom.xml clean install -Dmaven.test.skip=true -q
RUN mvn -f common-meeting-events/pom.xml clean install -Dmaven.test.skip=true -q

# Sonra audit-event-consumer-service'i package
RUN mvn -f audit-event-consumer-service/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/audit-event-consumer-service/target/*.jar app.jar
RUN chown app:app app.jar

EXPOSE 8099 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

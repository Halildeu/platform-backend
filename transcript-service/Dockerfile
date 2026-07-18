## Build Stage
# Faz 24 transcript-service — multi-module build: common-auth + common-export +
# common-meeting-events + transcript-service (mirrors endpoint-admin-service Dockerfile).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# common-auth dependency: pom + src
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src

# common-export dependency (shared CSV/Excel exporters): pom + src
COPY common-export/pom.xml ./common-export/pom.xml
COPY common-export/src ./common-export/src

# common-meeting-events dependency (shared meeting.event.v1 contract): pom + src
COPY common-meeting-events/pom.xml ./common-meeting-events/pom.xml
COPY common-meeting-events/src ./common-meeting-events/src

# transcript-service pom + src
COPY transcript-service/pom.xml ./transcript-service/pom.xml
COPY transcript-service/src ./transcript-service/src

# Reactor-local dependencies are not on Maven Central. Install them into the
# image build's local repository before packaging transcript-service.
RUN mvn -f common-auth/pom.xml clean install -Dmaven.test.skip=true -q
RUN mvn -f common-export/pom.xml clean install -Dmaven.test.skip=true -q
RUN mvn -f common-meeting-events/pom.xml clean install -Dmaven.test.skip=true -q

# Then package transcript-service.
RUN mvn -f transcript-service/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/transcript-service/target/*.jar app.jar
RUN chown app:app app.jar

EXPOSE 8098 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

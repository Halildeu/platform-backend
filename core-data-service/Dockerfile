## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Ortak auth kütüphanesini de build context içine al ki servis image build'i dış artifact'a bağlı olmasın.
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY core-data-service/pom.xml ./core-data-service/pom.xml
COPY core-data-service/src ./core-data-service/src

RUN mvn -f common-auth/pom.xml clean install -DskipTests
RUN mvn -f core-data-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/core-data-service/target/*.jar app.jar

EXPOSE 8092

ENTRYPOINT ["java", "-jar", "app.jar"]

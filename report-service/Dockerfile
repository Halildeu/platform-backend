## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY report-service/pom.xml ./report-service/pom.xml
COPY report-service/src ./report-service/src

RUN mvn -f common-auth/pom.xml clean install -DskipTests
RUN mvn -f report-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/report-service/target/*.jar app.jar

EXPOSE 8095

ENTRYPOINT ["java", "-jar", "app.jar"]

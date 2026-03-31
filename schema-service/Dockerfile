## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY schema-service/pom.xml ./schema-service/pom.xml
COPY schema-service/src ./schema-service/src

RUN mvn -f common-auth/pom.xml clean install -DskipTests
RUN mvn -f schema-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/schema-service/target/*.jar app.jar

EXPOSE 8096

ENTRYPOINT ["java", "-jar", "app.jar"]

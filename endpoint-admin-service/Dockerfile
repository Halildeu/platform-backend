## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY endpoint-admin-service/pom.xml ./endpoint-admin-service/pom.xml
RUN mvn -f endpoint-admin-service/pom.xml dependency:go-offline -q || true

COPY endpoint-admin-service/src ./endpoint-admin-service/src
RUN mvn -f endpoint-admin-service/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/endpoint-admin-service/target/*.jar app.jar
RUN chown app:app app.jar

EXPOSE 8096 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

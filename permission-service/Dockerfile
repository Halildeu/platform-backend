## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# permission-service, common-auth modülünü dahili olarak üretir.
# Build context backend kökü olduğundan ilgili iki modülü birlikte içeri alıyoruz.
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY permission-service/pom.xml ./permission-service/pom.xml
COPY permission-service/src ./permission-service/src

RUN mvn -f common-auth/pom.xml clean install -DskipTests
RUN mvn -f permission-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/permission-service/target/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]

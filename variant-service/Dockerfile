## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Sadece ihtiyaç duyulan modülleri içeri al (parent pom yok).
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY variant-service/pom.xml ./variant-service/pom.xml
COPY variant-service/src ./variant-service/src

# Önce common-auth'u lokal repo'ya install et
RUN mvn -f common-auth/pom.xml clean install -DskipTests

# Ardından variant-service jar'ını üret (testleri atla)
RUN mvn -f variant-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/variant-service/target/*.jar app.jar

EXPOSE 8091

ENTRYPOINT ["java", "-jar", "app.jar"]

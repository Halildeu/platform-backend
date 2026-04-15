## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Sadece ihtiyaç duyulan modülleri içeri al (parent pom yok).
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY user-service/pom.xml ./user-service/pom.xml
COPY user-service/src ./user-service/src

# Önce common-auth'u lokal repo'ya install et
RUN mvn -f common-auth/pom.xml clean install -DskipTests

# Ardından user-service jar'ını üret (testleri atla)
RUN mvn -f user-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

# D14: non-root user (K8s PSS restricted + runAsNonRoot uyumlu)
# UID 1000 platform-k8s-gitops Deployment securityContext ile eşleşir
RUN groupadd --gid 1000 --system app \
    && useradd  --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/user-service/target/*.jar app.jar
RUN chown app:app app.jar

# Main app (8089) + management actuator (8081) — application-k8s.yml ile eşleşir
EXPOSE 8089 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]

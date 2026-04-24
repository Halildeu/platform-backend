# --- Aşama 1: Projeyi Derleme (Build Stage) ---
# Java 21 ve Maven içeren, çoklu mimari destekli resmi bir temel imaj kullanıyoruz.
# Bu imaj hem Intel (amd64) hem de Apple Silicon (arm64) üzerinde doğal (native) çalışır.
FROM maven:3.9-eclipse-temurin-21 AS build

# Proje kodunu kopyalamak için bir çalışma dizini oluşturuyoruz.
WORKDIR /app

# Önce sadece pom.xml'i kopyalayıp bağımlılıkları indiriyoruz.
# Bu, kod değişikliği olmadığında her seferinde bağımlılıkları tekrar indirmemizi önler (Docker cache).
# Faz 19.8 — root context pattern (align with user-service/variant-service/etc.)
COPY auth-service/pom.xml .
RUN mvn dependency:go-offline

# Şimdi tüm proje kaynak kodunu kopyalıyoruz.
COPY auth-service/src ./src

# Maven ile projeyi derleyip çalıştırılabilir JAR dosyasını oluşturuyoruz.
# Testleri atlayarak süreci hızlandırıyoruz.
RUN mvn clean package -DskipTests


# --- Aşama 2: Çalıştırma İmajı (Runtime Stage) ---
# Sadece Java Runtime içeren, daha küçük, güvenli ve çoklu mimari destekli bir imaj kullanıyoruz.
# 'jammy' etiketi, Ubuntu 22.04 (Jammy Jellyfish) tabanlı olduğunu belirtir ve stabil bir seçenektir.
FROM eclipse-temurin:21-jre-jammy

# D14: non-root user (K8s PSS restricted + runAsNonRoot uyumlu)
# UID 1000 platform-k8s-gitops Deployment securityContext ile eşleşir
RUN groupadd --gid 1000 --system app \
    && useradd  --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar

# Main app (8088) + management actuator (8081) — application-k8s.yml ile eşleşir
EXPOSE 8088 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]
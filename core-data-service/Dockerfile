# --- Aşama 1: Projeyi Derleme (Build Stage) ---
# Java 21 ve Maven içeren, çoklu mimari destekli resmi bir temel imaj kullanıyoruz.
# Bu imaj hem Intel (amd64) hem de Apple Silicon (arm64) üzerinde doğal (native) çalışır.
FROM maven:3.9-eclipse-temurin-21 AS build

# Proje kodunu kopyalamak için bir çalışma dizini oluşturuyoruz.
WORKDIR /app

# Önce sadece pom.xml'i kopyalayıp bağımlılıkları indiriyoruz.
# Bu, kod değişikliği olmadığında her seferinde bağımlılıkları tekrar indirmemizi önler (Docker cache).
COPY pom.xml .
RUN mvn dependency:go-offline

# Şimdi tüm proje kaynak kodunu kopyalıyoruz.
COPY src ./src

# Maven ile projeyi derleyip çalıştırılabilir JAR dosyasını oluşturuyoruz.
# Testleri atlayarak süreci hızlandırıyoruz.
RUN mvn clean package -DskipTests


# --- Aşama 2: Çalıştırma İmajı (Runtime Stage) ---
# Sadece Java Runtime içeren, daha küçük, güvenli ve çoklu mimari destekli bir imaj kullanıyoruz.
# 'jammy' etiketi, Ubuntu 22.04 (Jammy Jellyfish) tabanlı olduğunu belirtir ve stabil bir seçenektir.
FROM eclipse-temurin:21-jre-jammy

# Çalışma dizini.
WORKDIR /app

# Derlenmiş JAR dosyasını build aşamasından bu yeni imajın içine kopyalıyoruz.
COPY --from=build /app/target/*.jar app.jar

# Uygulamanın çalışacağı portu dışarıya açıyoruz (bilgilendirme amaçlı).
EXPOSE 8080

# Container başladığında çalıştırılacak komut.
ENTRYPOINT ["java", "-jar", "app.jar"]


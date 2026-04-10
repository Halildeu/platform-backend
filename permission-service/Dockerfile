## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# 1) Copy POMs first for dependency layer caching
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY permission-service/pom.xml ./permission-service/pom.xml

# 2) Download dependencies (cached unless pom.xml changes)
RUN mvn -f common-auth/pom.xml dependency:go-offline -q || true
RUN mvn -f permission-service/pom.xml dependency:go-offline -q || true

# 3) Copy source (invalidates cache when code changes)
COPY common-auth/src ./common-auth/src
COPY permission-service/src ./permission-service/src

# 4) Build — always fresh when source or pom changes
RUN mvn -f common-auth/pom.xml clean install -DskipTests -q
RUN mvn -f permission-service/pom.xml clean package -Dmaven.test.skip=true -q

# 5) Extract the repackaged fat JAR (exclude .jar.original)
RUN cp permission-service/target/permission-service-*.jar app.jar \
    && java -jar app.jar --thin.list 2>/dev/null || true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/app.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]

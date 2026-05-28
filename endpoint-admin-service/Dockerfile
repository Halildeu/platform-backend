## Build Stage
# Faz 22.1.1b: multi-module build — common-auth + endpoint-admin-service
# (Codex 019df86f III review build-fix absorb 2026-05-05)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# common-auth dependency için pom + src
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src

# endpoint-admin-service pom + src
COPY endpoint-admin-service/pom.xml ./endpoint-admin-service/pom.xml
COPY endpoint-admin-service/src ./endpoint-admin-service/src

# common-auth'u önce install et (Maven local repo cache)
RUN mvn -f common-auth/pom.xml clean install -Dmaven.test.skip=true -q

# Sonra endpoint-admin-service'i package
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

# JPMS opens — operational unblock for Spring Boot 3.5.6 + Java 21
# CGLIB lazy-proxy of JDK classes.
#
# BE-023 EndpointComplianceService currently triggers this:
#   constructor takes `@Autowired(required = false) @Lazy Clock clock`
#   (compliance/EndpointComplianceService.java:122), which forces Spring
#   CGLIB to enhance java.time.Clock when building the lazy proxy.
#   Java 21 + Spring Boot LaunchedClassLoader cannot defineClass into
#   java.base without --add-opens, so the pod crashes on boot:
#
#   java.lang.IllegalAccessException: module java.base does not open
#     java.time to unnamed module @...
#   ClassLoader mismatch for [java.time.Clock]: JVM should be started
#     with --add-opens=java.base/java.lang=ALL-UNNAMED for
#     ClassLoader.defineClass to be accessible on
#     org.springframework.boot.loader.launch.LaunchedClassLoader
#
# Same class of issue applies to future lazy-injected JDK classes; the
# permanent source fix is to drop `@Lazy Clock` in favour of direct
# injection or ObjectProvider<Clock> with a Clock.systemUTC() fallback.
# That refactor is tracked as a follow-up — until it lands the JVM opens
# below keep the image bootable.
ENTRYPOINT ["java", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "--add-opens=java.base/java.time=ALL-UNNAMED", \
  "-jar", "app.jar"]

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl budget-service -am -DskipTests package

# The July 2026 OpenJDK CPU (advisory 2026-07-21) fixes CVE-2026-41254 and
# CVE-2026-47063 in the 21.0.12 line. As of 2026-08-15 eclipse-temurin has
# published no 21.0.12 jre-alpine artifact (newest is 21.0.11_10, June), so the
# runtime moves to BellSoft Liberica's alpine-musl JRE, which shipped 21.0.12 on
# 2026-07-27. Pinned to the exact release rather than a floating tag: the
# floating `21-jre-alpine` is how this image sat on the April JDK a month after
# the July CPU without anyone choosing that. CVE-2026-41254 is an integer
# overflow on the image-parsing side of the JDK, and this service decodes
# reporter-uploaded images in the JVM — accepting it was never on the table.
FROM bellsoft/liberica-openjre-alpine-musl:21.0.12-10
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build /workspace/budget-service/target/budget-service-*.jar app.jar
USER 10001:10001
EXPOSE 8101
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]

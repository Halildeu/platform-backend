FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl ethics-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# ES-306 (#2665): the published base still ships p11-kit 0.25.5-r2, which carries
# CVE-2026-2100 with a fix (0.26.2-r0) already in the Alpine repository. Waiting for
# upstream to rebuild would leave a High open for an unbounded time, so it is upgraded
# here. Targeted rather than a blanket `apk upgrade`: a broad upgrade changes packages
# nobody reviewed. If the fixed version ever disappears from the repository this build
# fails, which is the direction we want it to fail in.
RUN apk upgrade --no-cache p11-kit p11-kit-trust
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build /workspace/ethics-service/target/ethics-service-*.jar app.jar
USER 10001:10001
EXPOSE 8099
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]

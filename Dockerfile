FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 jobpilot \
    && adduser -S -D -H -u 10001 -G jobpilot jobpilot \
    && mkdir -p /var/lib/jobpilot/documents /tmp/jobpilot \
    && chown -R jobpilot:jobpilot /var/lib/jobpilot /tmp/jobpilot \
    && chmod 700 /var/lib/jobpilot/documents /tmp/jobpilot
WORKDIR /app
COPY --from=build /workspace/target/jobpilot-*.jar /app/jobpilot.jar
USER jobpilot
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp/jobpilot", "-jar", "/app/jobpilot.jar"]

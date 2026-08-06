# Node builds the Mini App only. It never reaches the runtime image: the final stage
# copies the jar and nothing else, so no npm, no node_modules and no source ship.
# 22.x satisfies Vite's "20.19+ or 22.12+" floor, matching .github/workflows/ci.yml.
FROM node:22-alpine AS mini-app
WORKDIR /mini-app
COPY mini-app/package.json mini-app/package-lock.json ./
RUN npm ci
COPY mini-app/ ./
# API mode is the only mode worth shipping; the default (mock) would serve fixtures.
RUN VITE_JOBPILOT_MODE=api npm run build

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
# Packaged as an ordinary classpath resource, which is what MiniAppWebConfig serves from.
COPY --from=mini-app /mini-app/dist ./src/main/resources/static/mini-app
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 jobpilot \
    && adduser -S -D -H -u 10001 -G jobpilot jobpilot \
    && mkdir -p /var/lib/jobpilot/documents /tmp/jobpilot \
    && chown -R jobpilot:jobpilot /var/lib/jobpilot /tmp/jobpilot \
    && chmod 700 /var/lib/jobpilot/documents /tmp/jobpilot
WORKDIR /app
COPY --from=build /workspace/target/jobpilot-*.jar /app/jobpilot.jar
# Build identity is baked here, after the cacheable layers, so a new commit only
# invalidates this layer. The runtime reads it through jobpilot.build.* and serves
# it from /health; leaving these unset at runtime keeps the baked values.
ARG BUILD_COMMIT=unknown
ARG JOBPILOT_VERSION=unknown
ENV BUILD_COMMIT=${BUILD_COMMIT} \
    JOBPILOT_VERSION=${JOBPILOT_VERSION}
LABEL org.opencontainers.image.revision=${BUILD_COMMIT} \
      org.opencontainers.image.version=${JOBPILOT_VERSION} \
      org.opencontainers.image.source=https://github.com/pashawkola33/JobPilot
USER jobpilot
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp/jobpilot", "-jar", "/app/jobpilot.jar"]

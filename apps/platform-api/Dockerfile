# syntax=docker/dockerfile:1
# Build stage: JDK 25 with the committed Maven Wrapper (no host Maven needed).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN sh ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN sh ./mvnw -B -ntp -DskipTests package

# Runtime stage: JRE 25, non-root, no build tools or secrets baked in.
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/platform-api-0.1.0.jar /app/platform-api.jar
USER app
EXPOSE 8080
# Bind to all container interfaces; the host publishes only on 127.0.0.1 (set in the compose file).
ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-jar", "/app/platform-api.jar"]

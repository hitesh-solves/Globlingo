# ---- Build stage: compile the Spring Boot jar with Maven ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw
RUN ./mvnw -q -B dependency:go-offline || true
COPY src ./src
COPY scripts ./scripts
RUN ./mvnw -q -B package -DskipTests

# ---- Runtime stage: slim JRE, non-root user ----
FROM eclipse-temurin:17-jre
# Python 3 + yt-dlp for the free caption fallback (no whisper/models).
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip \
    && pip3 install --break-system-packages --no-cache-dir yt-dlp \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd -r -u 1001 globlingo
RUN mkdir -p /app/data && chown -R globlingo:globlingo /app
COPY --from=build /app/target/globlingo-0.0.1-SNAPSHOT.jar app.jar
USER globlingo
EXPOSE 8081
# Render injects PORT; application.properties already maps server.port=${PORT:8081}
ENTRYPOINT ["java", "-jar", "app.jar"]

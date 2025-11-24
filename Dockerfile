# Stage 1: build the application with Maven
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom and sources
COPY pom.xml .
COPY src ./src

# Build the project (tests can run here; if нужно, можно добавить -DskipTests)
RUN mvn -q clean package

# Stage 2: run the application
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy shaded jar (fat jar) from build stage
COPY --from=build /app/target/*.jar app.jar

# Default Redis host/port inside Docker (overridable via env)
ENV REDIS_HOST=redis
ENV REDIS_PORT=6379

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
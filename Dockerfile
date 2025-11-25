# ===========================
# 1) Build phase
# ===========================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q package -DskipTests

# ===========================
# 2) Runtime phase
# ===========================
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/dashboard-aggregator*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
# ===========================
# 1) Build phase
# ===========================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Сначала только pom.xml, чтобы кэшировались зависимости
COPY pom.xml .
RUN mvn -q dependency:go-offline

# Потом исходники
COPY src ./src

# Собираем JAR, тесты в образе пропускаем
RUN mvn -q -DskipTests package

# ===========================
# 2) Runtime phase
# ===========================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Копируем СУЩЕСТВУЮЩИЙ артефакт.
# Shade-плагин кладёт fat-jar в этот файл:
# target/dashboard-aggregator-1.0-SNAPSHOT.jar
COPY --from=build /app/target/dashboard-aggregator-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
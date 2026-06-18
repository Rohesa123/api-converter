# --- Tahap build: kompilasi jar dengan Maven + JDK 21 ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependency dulu (layer ini di-reuse selama pom.xml tak berubah)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build aplikasi (test dijalankan di workflow CI, di sini di-skip agar build cepat)
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Tahap runtime: image ramping hanya berisi JRE + jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

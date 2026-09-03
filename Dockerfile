# Build the Spring Boot executable JAR.
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Copy the Maven descriptor first so dependency downloads can be cached.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# Run the application with a small JRE-only image.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

COPY --from=build /workspace/target/*.jar app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode dependency:go-offline

COPY src ./src
RUN mvn --batch-mode package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=build --chown=spring:spring /workspace/target/*.jar app.jar

USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

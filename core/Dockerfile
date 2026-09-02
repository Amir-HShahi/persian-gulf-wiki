# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY core/.mvn/ .mvn/
COPY core/mvnw core/pom.xml core/lombok.config ./
RUN ./mvnw -B dependency:go-offline
COPY core/src ./src
COPY .git ./.git
ARG APP_VERSION=0.0.0
RUN ./mvnw -B -DskipTests -Drevision=${APP_VERSION} package && \
    mv target/*.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
USER spring:spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

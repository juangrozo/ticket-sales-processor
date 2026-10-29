FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties lombok.config ./
COPY gradle gradle
COPY domain domain
COPY infrastructure infrastructure
COPY applications applications
RUN --mount=type=cache,target=/root/.gradle ./gradlew :app-service:bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S appuser -G app
WORKDIR /app
COPY --from=build /workspace/applications/app-service/build/libs/TicketSalesProcessor.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
# Run as a non-root user to avoid running the container with excessive privileges
USER appuser
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

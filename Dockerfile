FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/returnpilot-ai-0.1.0.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]

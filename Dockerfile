FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

COPY target/id_card_service-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
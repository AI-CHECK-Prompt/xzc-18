FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/icu-monitor-*.jar app.jar
EXPOSE 8080 9100 9200
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

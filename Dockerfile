FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B --no-transfer-progress -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /app /data
COPY --from=build /workspace/target/commerceflow-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]

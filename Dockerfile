FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
# The API v2 contract is packaged for the opt-in /docs page.
COPY api-v2/openapi.json api-v2/openapi.json
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup --system app \
    && adduser --system --ingroup app app

WORKDIR /app
COPY --from=build --chown=app:app \
    /workspace/target/fall-festival-server-*.jar app.jar

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jdk-alpine-3.24 AS build

RUN apk add --no-cache libwebp-tools \
    && command -v cwebp \
    && command -v dwebp \
    && command -v webpinfo \
    && command -v img2webp \
    && cwebp -version \
    && dwebp -version \
    && webpinfo -version \
    && img2webp -version

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
# The API v2 contract is packaged for the opt-in /docs page.
COPY api-v2/openapi.json api-v2/openapi.json
RUN ./mvnw --batch-mode --no-transfer-progress \
    -Dmedia.codec.external-tools.required=true \
    -Dtest=ExternalWebpToolsCompatibilityTest,GoodsImageProcessorAlpineIntegrationTest \
    test
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine-3.24 AS runtime

RUN apk add --no-cache libwebp-tools \
    && command -v cwebp \
    && command -v dwebp \
    && command -v webpinfo \
    && command -v img2webp \
    && cwebp -version \
    && dwebp -version \
    && webpinfo -version \
    && img2webp -version \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /var/lib/espero/media \
    && chown app:app /var/lib/espero/media

WORKDIR /app
COPY --from=build --chown=app:app \
    /workspace/target/fall-festival-server-*.jar app.jar

# Goods images are stored here. Mount a named volume at this path so the files
# survive the container being recreated; a new named volume copies this
# directory's ownership. The fixed UID/GID 10001 lets a host directory be
# prepared for a bind mount instead.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    FESTIVAL_MEDIA_STORAGE_ROOT=/var/lib/espero/media
VOLUME ["/var/lib/espero/media"]

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

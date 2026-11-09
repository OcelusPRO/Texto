# ---- Frontend (React + Tailwind) ----
FROM node:24-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- Backend (Kotlin + Ktor), embarque web/dist dans le jar ----
FROM eclipse-temurin:25-jdk-alpine-3.24 AS app
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Fin de ligne Unix (le dépôt peut être extrait sous Windows).
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src/ src/
COPY cli/ cli/
COPY --from=web /src/web/dist web/dist
RUN ./gradlew --no-daemon buildFatJar -x test

# ---- Image finale ----
FROM eclipse-temurin:25-jre-alpine-3.24
# Polices pour les images OpenGraph générées côté serveur.
RUN apk add --no-cache fontconfig ttf-dejavu
WORKDIR /app
COPY --from=app /src/build/libs/app.jar /opt/texto/app.jar
# Assets Factorio optionnels (produits par tools/factorio-exporter, jamais versionnés).
COPY factorio-assets/ /opt/texto/factorio/
ENV TEXTO_FACTORIO_ASSETS=/opt/texto/factorio \
    TEXTO_LEGACY_PAGES=/app/pages
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:MaxRAMPercentage=75", "-jar", "/opt/texto/app.jar"]

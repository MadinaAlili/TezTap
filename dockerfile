# ----------------------------VERSION 1 DOCKERFILE--------------------
# # -------- BUILD STAGE --------
# FROM maven:3.9-eclipse-temurin-17 AS build
# WORKDIR /app
#
# COPY pom.xml .
# RUN mvn dependency:go-offline
#
# COPY src ./src
# RUN mvn clean package -DskipTests
#
# # -------- RUN STAGE --------
# FROM eclipse-temurin:17-jdk
# WORKDIR /app
#
# COPY --from=build /app/target/*.jar app.jar
#
# ENTRYPOINT ["java", "-jar", "app.jar"]


# ---------------------VERSION 2 DOCKERFILE---------------------------
# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
# Microsoft's official Playwright image for Java.
# It is Ubuntu (Jammy) based and ships with:
#   - All Chromium system-level dependencies (fonts, libs, etc.)
#   - Chromium, Firefox, WebKit browser binaries pre-downloaded
#   - OpenJDK 17
# This is far safer than manually apt-installing ~50 Chromium deps on a bare JRE image.
FROM mcr.microsoft.com/playwright/java:v1.44.0-jammy

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# create logs folder and fix permissions
RUN mkdir -p /app/logs && chown -R pwuser:pwuser /app

# Tell the Playwright Java SDK where the pre-installed browsers live.
# Without this it tries to download them at runtime and fails (no internet, wrong path).
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# Heap is intentionally squeezed to ~300m. Metaspace capped at 128m.
# NO MaxRAMPercentage allowed, to ensure Chromium has room to breathe.
# ENV JAVA_OPTS="-Xmx300m -Xms300m -XX:MaxMetaspaceSize=128m -XX:+UseContainerSupport"

# Drop root — the Playwright image creates a non-root user called pwuser
USER pwuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
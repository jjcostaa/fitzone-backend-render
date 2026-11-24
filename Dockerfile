# ==========================================================
# FitZone VIII Semestre — Multi-Stage Cloud Dockerfile
# Incluye: Spring Boot (Java 21) + Python 3 (PuLP + ML)
# Compatible con Render, Railway, AWS ECS/App Runner, Azure
# ==========================================================

# --- Etapa 1: Compilación del proyecto Spring Boot ---
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Etapa 2: Runtime ligero (Java 21 + Python 3 ML/PuLP) ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Instalar Python 3, pip y venv para los servicios de Optimización y Minería de Datos II
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip python3-venv curl && \
    rm -rf /var/lib/apt/lists/*

# Entorno virtual Python dedicado
COPY requirements.txt .
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir -r requirements.txt

# Copiar artefacto Spring Boot compilado
COPY --from=build /app/target/*.jar app.jar

# Copiar scripts de Optimización, Minería de Datos II y recursos necesarios
COPY modelo_optimizacion_json.py ./
COPY reglamento.txt ./
COPY dbfitzone ./dbfitzone
COPY ml ./ml

# Entrenar/verificar artefactos ML si no existen todavía
RUN /opt/venv/bin/python ml/train_models.py || true

# Variables por defecto para entorno contenedor
ENV PORT=8080
ENV PYTHON_BIN=/opt/venv/bin/python

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -f http://localhost:${PORT}/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java -Xmx512m -Dserver.port=${PORT} -jar /app/app.jar"]

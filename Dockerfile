# Imagen de la aplicación. Se construye en dos etapas para que la imagen final no cargue con Maven
# ni con el código fuente: solo lleva un JRE y el jar.
#
#   docker build -t tareas .
#   docker run --rm -p 8080:8080 -v tareas-datos:/datos tareas
#
# El volumen es opcional en local, pero sin él los archivos JSON viven dentro del contenedor y se
# pierden al borrarlo.

# ----------------------------------------------------------------- Construcción
FROM maven:3.9-eclipse-temurin-21 AS construccion

WORKDIR /proyecto

# El pom va solo y antes que el código: mientras no cambien las dependencias, Docker reutiliza la
# capa con el repositorio ya descargado y la construcción tarda segundos en vez de minutos.
COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean package

# ------------------------------------------------------------------- Ejecución
FROM eclipse-temurin:21-jre-alpine AS ejecucion

# Un usuario sin privilegios: si alguien se cuela por la aplicación, no es root dentro del
# contenedor. Es la configuración por defecto que se espera de una imagen publicada.
RUN addgroup --system tareas && adduser --system --ingroup tareas tareas

WORKDIR /aplicacion

# El directorio de datos se crea aquí y no al vuelo para poder darle el dueño correcto: el proceso
# corre como «tareas» y necesita poder escribir el JSON.
RUN mkdir -p /datos && chown -R tareas:tareas /datos

COPY --from=construccion /proyecto/target/tareas-*.jar aplicacion.jar

USER tareas

EXPOSE 8080

# Las rutas apuntan a /datos, que es donde se monta el volumen. Sin esto los archivos irían al
# directorio de trabajo, dentro de la capa de escritura del contenedor.
ENV APP_ALMACEN_RUTA=/datos/tareas.json
ENV APP_ALMACEN_RUTA_DE_FONDOS=/datos/fondos.json

ENTRYPOINT ["java", "-jar", "aplicacion.jar"]

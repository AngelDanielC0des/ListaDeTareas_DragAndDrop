# Lista de tareas — versión con persistencia

Aplicación web de lista de tareas: API REST en Java 21 con Spring Boot 4 y frontend en HTML, CSS y
JavaScript sin framework ni paso de build. Los datos se guardan en un archivo JSON, sin base de
datos.

## Las tres versiones

| Rama | Qué añade | Dónde viven las tareas |
|---|---|---|
| `listatareasv0` | CRUD completo sobre la API REST | memoria |
| **`listatareasv1`** ← estás aquí | persistencia entre arranques | archivo JSON |
| `master` | drag & drop, guardado automático, errores centralizados | archivo JSON |

```bash
git diff listatareasv0 listatareasv1   # lo que cuesta añadir la persistencia
git diff listatareasv1 master          # lo que cuesta añadir el arrastre
```

## Requisitos

**Java 21.** Maven no hace falta: el proyecto trae su propio wrapper (`mvnw` en Linux y macOS,
`mvnw.cmd` en Windows), que se descarga la versión correcta la primera vez.

## Arrancar

```powershell
.\mvnw.cmd spring-boot:run
```

Y abrir <http://localhost:8080>. Si el puerto está ocupado:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

Pruebas:

```powershell
.\mvnw.cmd test
```

Para arrancar con el log detallado:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=dev"
```

## Dónde se guardan los datos

En `datos/tareas.json`, relativo al directorio desde el que se arranca la app. Se crea solo la
primera vez que se añade una tarea. La ruta se cambia en `application.properties`:

```properties
app.almacen.ruta=datos/tareas.json
```

El archivo contiene un array con los tres campos de cada tarea, y **el orden del array es el orden
de la lista**:

```json
[
  { "id" : 1, "texto" : "Repasar HTML", "completada" : false },
  { "id" : 2, "texto" : "Repasar CSS", "completada" : true }
]
```

La escritura es atómica: se guarda en un archivo temporal y luego se mueve sobre el definitivo, así
que si el proceso muere a mitad el archivo bueno queda intacto en lugar de truncado. Hay un
test que lo comprueba: provoca un fallo de escritura y verifica que el archivo anterior se sigue
pudiendo cargar entero.

## API

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| `GET` | `/tarea` | — | `200` con las tareas en orden |
| `GET` | `/tarea/{id}` | — | `200` · `404` si no existe |
| `POST` | `/tarea` | `{"texto":"…"}` | `201` + `Location`, se añade al final |
| `PUT` | `/tarea/{id}` | `{"texto":"…","completada":false}` | `200` · `404` si no existe |
| `DELETE` | `/tarea/{id}` | — | `204` · `404` si no existe |

Los errores se devuelven siempre en formato `ProblemDetail` (RFC 9457):

```json
{
  "type": "urn:tareas:error:validacion",
  "title": "Datos inválidos",
  "status": 400,
  "detail": "Revisa los campos indicados en la propiedad «errores».",
  "errores": { "texto": "El texto de la tarea no puede estar vacío" }
}
```

## Estructura

```
src/main/java/angel/xtd/tareas/
├── controller/TareasController.java   API REST: solo el camino feliz
├── service/TareasService.java         lógica de negocio
├── almacen/AlmacenTareas.java         estado en memoria + escritura atómica del JSON
├── dto/                               Tarea (3 campos) y los cuerpos de petición
├── error/                             excepciones de dominio + @RestControllerAdvice
└── config/PropiedadesAlmacen.java     ruta del archivo

src/main/resources/
├── application.properties             configuración y ruta del archivo de datos
├── messages.properties                traduce al español los errores del framework
└── static/
    ├── index.html                     HTML semántico + <template> de la tarjeta
    ├── css/estilos.css                especificidad plana, BEM, rejilla con auto-fill
    └── js/
        ├── api.js                     única puerta hacia la API; interpreta los errores
        └── app.js                     estado, pintado y manejadores

src/test/java/angel/xtd/tareas/
├── almacen/AlmacenTareasTest.java         la E/S: rollback y archivo intacto si falla
├── controller/TareasControllerTest.java   el contrato HTTP, con el servicio simulado
├── service/TareasServiceTest.java         la lógica, contra un archivo temporal
└── RecursosEstaticosTest.java             que el HTML y el servidor no se contradigan
```

`AlmacenTareas` **no es un repository**: no hay ORM ni base de datos. Es el guardián del estado en
memoria y de la escritura del archivo, separado del servicio para que el `Files.move` y el
`ObjectMapper` no entierren la lógica de negocio.

## Integración continua

Cada empujón dispara un workflow de GitHub Actions que compila y ejecuta los tests en Ubuntu con
Java 21 (`.github/workflows/build.yml`).

## Cómo funciona

- **El `id` es identidad y no cambia nunca.** El orden es la posición dentro del array del JSON, así
  que la tarea solo necesita los tres campos que se guardan, sin ningún campo de orden.
- **El id sale de un contador propio, no del tamaño de la lista.** Con `size()` habría colisiones:
  crea tres tareas (1, 2, 3), borra la 2 y la siguiente recibiría el id 3, machacando una existente.
- **El estado del navegador vive en una variable, no en el DOM.** Tras cada cambio se repinta la
  lista entera desde ese estado; con decenas de tareas es instantáneo y evita toda una clase de
  errores por tener la verdad en dos sitios.
- **Los errores se traducen en un solo sitio.** El controlador no tiene ni un `try/catch`: el
  servicio lanza excepciones con significado y `ManejadorErroresGlobal` las convierte en códigos
  HTTP. Añadir un endpoint no requiere escribir manejo de errores.
- **Ese manejador no atrapa `Exception`, y es a propósito.** Spring se queda con el primer advice
  que tenga cualquier método aplicable, y `Exception` casa con todo: un catch-all aquí dejaría sin
  ejecutar al manejador de Spring Boot y convertiría los 405 y 415 en 500. Hay tests que lo cubren.

## Uso

- **Añadir**: escribir arriba y pulsar Enter o «Añadir».
- **Completar**: marcar la casilla de la tarjeta.
- **Editar**: botón «Editar», cambiar el texto y «Guardar» (o «Cancelar»).
- **Borrar**: botón «Borrar», que pide confirmación.

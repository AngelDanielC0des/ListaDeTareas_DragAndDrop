# Lista de tareas — versión en memoria

Aplicación web de lista de tareas: API REST en Java 21 con Spring Boot 4 y frontend en HTML, CSS y
JavaScript sin framework ni paso de build.

**Las tareas viven solo en memoria.** Al arrancar la aplicación la lista está vacía y se va llenando
según se usa; al parar el servidor se pierde todo. No se crea ningún archivo ni hace falta base de
datos.

## Las tres versiones

| Rama | Qué añade | Dónde viven las tareas |
|---|---|---|
| **`listatareasv0`** ← estás aquí | CRUD completo sobre la API REST | memoria |
| `listatareasv1` | persistencia entre arranques | archivo JSON |
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

## API

Todo se recibe y se devuelve en JSON.

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| `GET` | `/tarea` | — | `200` con las tareas en orden |
| `GET` | `/tarea/{id}` | — | `200` con la tarea · `404` si no existe |
| `POST` | `/tarea` | `{"texto":"…"}` | `201` + `Location`, se añade al final |
| `PUT` | `/tarea/{id}` | `{"texto":"…","completada":false}` | `200` · `404` si no existe |
| `DELETE` | `/tarea/{id}` | — | `204` · `404` si no existe |

Una tarea tiene exactamente tres campos:

```json
{ "id": 1, "texto": "Repasar HTML", "completada": false }
```

Los errores se devuelven en formato `ProblemDetail` (RFC 9457):

```json
{
  "title": "Datos inválidos",
  "status": 400,
  "detail": "Revisa los datos enviados: hay algún campo que no es válido."
}
```

Los genera Spring, no código propio: esta versión no tiene manejador de errores. Sus textos se
traducen al español en `messages.properties`.

Ejemplo con `curl`:

```bash
curl -X POST localhost:8080/tarea -H "Content-Type: application/json" -d "{\"texto\":\"Repasar HTML\"}"
curl localhost:8080/tarea
curl -X PUT localhost:8080/tarea/1 -H "Content-Type: application/json" -d "{\"texto\":\"Repasar HTML\",\"completada\":true}"
curl -X DELETE localhost:8080/tarea/1
```

## Estructura

Seis clases, dos capas:

```
src/main/java/angel/xtd/tareas/
├── TareasApplication.java             arranque
├── controller/TareasController.java   los 5 endpoints; traduce el «no existe» a 404
├── service/TareasService.java         la lista en memoria + la lógica
└── dto/
    ├── Tarea.java                     el record de 3 campos
    ├── CrearTareaPeticion.java        cuerpo del POST, con @Valid
    └── ActualizarTareaPeticion.java   cuerpo del PUT, con @Valid

src/main/resources/
├── application.properties             configuración
├── messages.properties                traduce al español los errores del framework
└── static/
    ├── index.html                     HTML semántico + <template> de la tarjeta
    ├── css/estilos.css                especificidad plana, BEM, rejilla con auto-fill
    └── js/
        ├── api.js                     única puerta hacia la API
        └── app.js                     estado, pintado y manejadores

src/test/java/angel/xtd/tareas/
├── controller/TareasControllerTest.java   el contrato HTTP, con el servicio simulado
├── service/TareasServiceTest.java         la lógica, sin simular nada
└── RecursosEstaticosTest.java             que el HTML y el servidor no se contradigan
```

## Integración continua

Cada empujón dispara un workflow de GitHub Actions que compila y ejecuta los tests en Ubuntu con
Java 21 (`.github/workflows/build.yml`).

## Cómo funciona

- **El `id` es identidad y no cambia nunca.** El orden es la posición dentro de la lista, así que la
  tarea solo necesita los tres campos que se envían por JSON, sin ningún campo de orden.
- **El id sale de un contador propio, no del tamaño de la lista.** Con `size()` habría colisiones:
  crea tres tareas (1, 2, 3), borra la 2 y la siguiente recibiría el id 3, machacando una existente.
  Hay un test de regresión.
- **El servicio devuelve `Optional` y `boolean`** en lugar de lanzar excepciones cuando la tarea no
  existe. Así el controlador responde el 404 él mismo y esta versión no necesita ni excepciones
  propias ni un manejador global de errores.
- **Los métodos del servicio son `synchronized`** porque el servidor atiende varias peticiones a la
  vez y todas comparten la misma lista. Sin ello, dos altas simultáneas podrían recibir el mismo id.
- **El estado del navegador vive en una variable, no en el DOM.** Tras cada cambio se repinta la
  lista entera desde ese estado, lo que evita toda una clase de errores por tener la verdad en dos
  sitios.
- **El texto del usuario se pinta con `textContent`**, nunca concatenando HTML, así que no puede
  inyectar marcado.

## Uso

- **Añadir**: escribir arriba y pulsar Enter o «Añadir».
- **Completar**: marcar la casilla de la tarjeta.
- **Editar**: botón «Editar», cambiar el texto y «Guardar» (o «Cancelar»).
- **Borrar**: botón «Borrar», que pide confirmación.

Al recargar la página las tareas siguen ahí, porque las guarda el servidor. Al **reiniciar el
servidor**, no: para eso está la rama `listatareasv1`.

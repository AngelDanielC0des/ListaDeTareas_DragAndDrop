# Lista de tareas

Aplicación web para gestionar una lista de tareas: **API REST en Java 21 con Spring Boot 4.1** y un
frontend en HTML, CSS y JavaScript **sin framework ni paso de compilación**. Los datos se guardan en
archivos JSON; no hace falta base de datos.

Puedes añadir tareas, marcarlas como completadas, editarlas, reordenarlas arrastrando o con el
teclado, borrarlas con opción de deshacer, ponerles una imagen de fondo y cambiar entre tema claro y
oscuro. Todo se guarda solo: no hay botón de guardar.

## Las tres versiones

El proyecto vive en tres ramas encadenadas, cada una un paso más que la anterior, para poder leer la
evolución por partes.

| Rama | Qué añade | Dónde viven las tareas |
|---|---|---|
| `listatareasv0` | CRUD completo sobre la API REST | memoria |
| `listatareasv1` | persistencia entre arranques | archivo JSON |
| **`master`** ← estás aquí | arrastre, guardado automático, fondos, temas | archivos JSON |

```bash
git diff listatareasv0 listatareasv1   # lo que cuesta añadir la persistencia
git diff listatareasv1 master          # lo que cuesta añadir todo lo demás
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

## Empaquetar y ejecutar

```powershell
.\mvnw.cmd clean package
java -jar target\tareas-0.0.1-SNAPSHOT.jar
```

**La interfaz web viaja dentro del jar**, así que no hay nada más que desplegar: con Java 21
instalado, ese archivo es la aplicación entera.

Cualquier propiedad se puede cambiar al arrancar, sin tocar el `application.properties`:

```powershell
java -jar target\tareas-0.0.1-SNAPSHOT.jar --server.port=9000 --app.almacen.ruta=C:\datos\tareas.json
```

> **Ojo con el directorio.** La ruta por defecto `datos/tareas.json` es **relativa al directorio
> desde el que se lanza**, no a donde esté el jar. Ejecutarlo desde otra carpeta crea el archivo en
> otra carpeta; si eso molesta, pásale una ruta absoluta como en el ejemplo de arriba.

## Cómo se usa

- **Añadir**: escribir arriba y pulsar Enter o el botón **+**. La tecla <kbd>n</kbd> enfoca el campo.
- **Completar**: marcar la casilla de la tarjeta.
- **Editar**: botón «Editar». Se guarda solo 500 ms después de dejar de escribir. <kbd>Enter</kbd>
  confirma, <kbd>Mayús</kbd>+<kbd>Enter</kbd> hace un salto de línea, <kbd>Escape</kbd> cancela.
- **Reordenar**: arrastrar por el asa ⠿, o con <kbd>Ctrl</kbd>+<kbd>↑</kbd>/<kbd>↓</kbd> teniendo el
  asa enfocada.
- **Ver texto completo**: botón «Ver más», que solo aparece si el texto está recortado.
- **Fondo**: botón «Fondo» de la tarjeta, que abre un selector con cinco imágenes o ninguna.
- **Borrar**: botón «Borrar». La tarea desaparece y quedan unos segundos para pulsar «Deshacer», que
  la devuelve a su posición exacta.
- **Tema**: claro, oscuro o el del sistema, con el selector de la cabecera. La elección se recuerda
  en este navegador.

## La API

Todo se recibe y se devuelve en JSON.

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| `GET` | `/tarea` | — | `200` con las tareas en orden |
| `GET` | `/tarea/{id}` | — | `200` · `404` si no existe |
| `POST` | `/tarea` | `{"texto":"…"}` | `201` + `Location`, se añade al final |
| `PUT` | `/tarea/{id}` | `{"texto":"…","completada":false}` | `200` · `404` si no existe |
| `PATCH` | `/tarea/{id}/completada` | `{"completada":true}` | `200` · `404` si no existe |
| `DELETE` | `/tarea/{id}` | — | `204` · `404` si no existe |
| `PUT` | `/tarea/orden` | `{"ids":[3,1,2]}` | `200` · `409` si no es una permutación exacta |
| `GET` | `/tarea/fondo` | — | `200` con `{id: fondo}` de las tareas que tienen uno |
| `PUT` | `/tarea/{id}/fondo` | `{"fondo":"ondas"}` | `200` con el mapa actualizado · `404` si no existe |
| `GET` | `/tarea/configuracion` | — | `200` con los límites del servidor |

Una tarea tiene **exactamente tres campos**:

```json
{ "id": 1, "texto": "Repasar HTML", "completada": false }
```

Los errores usan siempre el formato `ProblemDetail` (RFC 9457):

```json
{
  "type": "urn:tareas:error:validacion",
  "title": "Datos inválidos",
  "status": 400,
  "detail": "Revisa los campos indicados en la propiedad «errores».",
  "errores": { "texto": "El texto de la tarea no puede estar vacío" }
}
```

## Dónde se guardan los datos

En `datos/`, relativo al directorio desde el que se arranca la aplicación. Los archivos se crean
solos la primera vez que hacen falta. Las rutas se cambian en `application.properties`:

```properties
app.almacen.ruta=datos/tareas.json
app.almacen.ruta-de-fondos=datos/fondos.json
```

`tareas.json` es un array donde **el orden del array es el orden de la lista**:

```json
[
  { "id" : 1, "texto" : "Repasar HTML", "completada" : false },
  { "id" : 2, "texto" : "Repasar CSS", "completada" : true }
]
```

`fondos.json` asocia cada tarea con su imagen, **en un archivo aparte a propósito**: así una tarea
conserva exactamente sus tres campos y la decoración no se mezcla con los datos.

```json
{ "2" : "aurora" }
```

La escritura es **atómica**: se guarda en un archivo temporal y luego se mueve sobre el definitivo,
así que un corte a mitad no deja el archivo truncado. Si la escritura falla, el cambio se deshace
también en memoria para que las dos no se desincronicen.

## Estructura

```
src/main/java/angel/xtd/tareas/
├── TareasApplication.java             arranque
├── controller/TareasController.java   API REST: solo el camino feliz, sin un try/catch
├── service/TareasService.java         lógica de negocio
├── almacen/AlmacenTareas.java         las tareas en memoria + escritura atómica
├── almacen/AlmacenFondos.java         TreeMap<id, fondo> en su propio archivo
├── dto/                               Tarea (3 campos), Fondo y los cuerpos de petición
├── error/                             excepciones de dominio + el @RestControllerAdvice
└── config/PropiedadesAlmacen.java     rutas de los archivos

src/main/resources/
├── application.properties             configuración
├── messages.properties                traduce al español los errores del framework
└── static/
    ├── index.html                     HTML semántico + <template> de la tarjeta
    ├── css/estilos.css                especificidad plana, BEM, mobile first
    ├── img/                           las 5 imágenes de fondo
    └── js/
        ├── api.js          única puerta hacia la API; interpreta los errores
        ├── preferencias.js único sitio que toca localStorage (solo el tema)
        ├── estado.js       única fuente de verdad del cliente
        ├── vista.js        todo lo que toca el DOM
        ├── arrastre.js     SortableJS + reordenación por teclado
        └── app.js          orquestador

src/test/java/angel/xtd/tareas/
├── TareasApplicationTest.java             que el contexto de Spring arranca
├── almacen/AlmacenTareasTest.java         la E/S: rollback y archivo intacto si falla
├── almacen/AlmacenFondosTest.java         la asociación tarea → fondo
├── controller/TareasControllerTest.java   el contrato HTTP, con el servicio simulado
├── service/TareasServiceTest.java         la lógica, contra un archivo temporal
└── RecursosEstaticosTest.java             que el HTML y el servidor no se contradigan
```

`AlmacenTareas` y `AlmacenFondos` **no son repositories**: no hay ORM ni base de datos. Son los
guardianes del estado compartido y de la escritura, separados del servicio para que el `Files.move` y
el `ObjectMapper` no entierren la lógica de negocio.

## Por qué está hecho así

Las decisiones que no se deducen leyendo el código.

### El `id` es identidad; el orden es la posición

El `id` no cambia nunca y el orden es la posición dentro del array del JSON, así que la tarea no
necesita ningún campo de orden. **Renumerar los ids al arrastrar rompería la identidad**: si el
navegador tuviera un `DELETE /tarea/1` en vuelo, ese borrado acabaría aplicándose a otra tarea.

Reordenar es una sola petición atómica, `PUT /tarea/orden`, y el servidor comprueba que lo recibido
es una **permutación exacta** de lo que tiene: ni repetidos, ni ids desconocidos, ni tareas que se
quedan fuera. Sin esa comprobación, un cliente con la lista desactualizada podría borrar tareas sin
querer al reordenar.

### `ArrayList`, no `TreeMap`, para las tareas

Con el orden como posición, la clave de un mapa sería un entero denso (0..N‑1), que es justo la
definición de un array. Además la operación estrella —mover una tarea— es la peor para un árbol:
O(n·log n) reasignando claves frente a O(n) de un `System.arraycopy`.

No se implementa `Comparable`: sirve para ordenar **según los datos**, pero el arrastre ordena
**según el usuario**, que no es un criterio calculable a partir de los campos de la tarea.

### El id sale de un contador, no del tamaño de la lista

Con `size()` habría colisiones: crea tres tareas (1, 2, 3), borra la 2 y la siguiente recibiría el
id 3, machacando una existente. Hay un test de regresión.

### El fondo vive fuera de la tarea

Una tarea son tres campos y así se queda, así que el fondo se guarda como un `TreeMap<Integer, Fondo>`
en su propio archivo. Va **en el servidor y no en el navegador** porque no es una preferencia de
vista: si eliges un fondo en el ordenador, esperas verlo también en el móvil.

Es un `TreeMap` para que los ids salgan ordenados en el archivo y las diferencias se lean bien.
Elegir «ninguno» borra la entrada en vez de guardarla, al borrar una tarea se olvida su fondo, y al
arrancar se descartan los huérfanos por si el proceso murió entre las dos escrituras.

**El fondo se pinta en un pseudoelemento por debajo del contenido**, no como `background` de la
tarjeta: es lo que permite bajarle la opacidad sin atenuar el texto, que es lo que mantiene el
contraste en nivel AA.

### Qué se guarda en el navegador

`localStorage` guarda **una sola cosa**: el tema. Las tareas no, porque el servidor es su fuente de
verdad y duplicarlas crearía una segunda que habría que mantener sincronizada.

El tema se aplica desde un script en línea en el `<head>` y no desde un módulo: los módulos se
ejecutan después del primer pintado, así que leerlo más tarde produciría un fogonazo del tema claro.

### Borrar se puede deshacer, y por eso la petición se retrasa

En vez de un `confirm()` que bloquea, la tarea desaparece y quedan unos segundos para rectificar.
**El `DELETE` se retrasa a propósito**: mandarlo de inmediato y «deshacer» creando la tarea otra vez
le daría un id nuevo y la dejaría al final de la lista, no en su sitio. Si cierras la pestaña con un
borrado a medias, se envía con `keepalive`.

### Los errores se traducen en un solo sitio

`ManejadorErroresGlobal` extiende `ResponseEntityExceptionHandler`, que es el punto de extensión que
Spring diseñó para esto: la autoconfiguración de Boot lleva `@ConditionalOnMissingBean`, así que al
declarar la subclase la de Boot se aparta y esta atiende tanto las excepciones del dominio como las
del framework. Los textos de estas últimas se traducen en `messages.properties`.

El controlador no tiene **ni un `try/catch`**: escribe solo el camino feliz.

### El estado del cliente vive en una variable, no en el DOM

El DOM es siempre una proyección del estado, nunca al revés. Los cambios se pintan antes de que
conteste el servidor, pero si lo rechaza se deshacen y se avisa, y las respuestas que llegan fuera de
orden se descartan con un testigo de secuencia.

### El atributo `hidden` hay que reafirmarlo en el CSS

El navegador oculta lo marcado con `hidden` mediante una regla de su propia hoja de estilos, y
**cualquier `display` que declare el autor le gana**. Como `.tarea__texto` lleva `-webkit-box` para
el recorte, sin una regla `[hidden] { display: none !important; }` al editar se verían a la vez el
párrafo y el cuadro de edición. Es la única excepción al «sin `!important`» del proyecto.

### Accesibilidad

Cada botón dice **de qué tarea es** mediante `aria-label`; sin eso, un lector de pantalla recorrería
la lista diciendo «Borrar, botón» sin nombrar nunca la tarea. Hay enlace de salto al listado, región
`aria-live` que anuncia los cambios, `aria-expanded` en «Ver más», iconos con `aria-hidden` y
devolución del foco tras borrar y tras deshacer.

El selector de fondo es un `<dialog>` nativo: trae el foco atrapado, el cierre con Escape y la
devolución del foco al botón que lo abrió, sin ARIA escrita a mano.

## Diseño e interfaz

Hay tema claro y oscuro, que por defecto sigue al del sistema. La rejilla es de 2 columnas en móvil,
3 a partir de 600 px y 4 a partir de 1024 px, y cada tarjeta toma su altura natural; el texto se
recorta a 2 líneas hasta que se despliega.

Por debajo de 480 px las acciones de la tarjeta se quedan solo con su icono: con dos columnas la
tarjeta mide unos 142 px y tres botones con su palabra no caben. El nombre para lectores de pantalla
no cambia, porque lo aporta el `aria-label`.

El CSS sigue tres reglas: **especificidad plana** (una sola clase por selector, sin ids ni
anidamiento), nomenclatura **BEM**, y todo color, espacio y radio sale de una variable de `:root`.

### Cambiar las imágenes de fondo

Están en `src/main/resources/static/img/` como cinco SVG. Para poner las tuyas basta con sustituir
esos archivos manteniendo el nombre; si usas otro formato, hay que cambiar la extensión en las cinco
reglas `.tarea--fondo-*` de `estilos.css` y en `abrirSelectorDeFondo` de `vista.js`.

## Integración continua

Cada empujón dispara un workflow de GitHub Actions que compila y ejecuta los tests en Ubuntu con
Java 21 (`.github/workflows/build.yml`).

## Dependencias

Las de Spring Boot y **una sola del frontend**:
[SortableJS](https://github.com/SortableJS/Sortable) 1.15.7 (MIT), vendorizada en
`static/js/vendor/` para que la aplicación funcione sin conexión y sin paso de compilación. Se
descartó hacer el arrastre a mano porque el layout es una rejilla de 2 a 4 columnas con tarjetas de
altura variable, donde calcular el destino es bastante más que comparar una coordenada.

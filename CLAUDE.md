# Lista de tareas — convenciones del proyecto

Aplicación de gestión de tareas: API REST en Java 21 con Spring Boot 4.1 y frontend en HTML, CSS y
JavaScript sin framework. Todo el código, los nombres y los comentarios van **en español**.

## Restricciones que no se negocian

1. **Una tarea son exactamente tres campos: `id`, `texto`, `completada`.** No se añade ninguno más,
   nunca. Todo lo demás que quiera asociarse a una tarea (el fondo, el grupo) vive en su propio
   recurso, en su propio archivo, con su propio almacén. Es la restricción del enunciado y de ella
   salen casi todas las decisiones de diseño del proyecto.
2. **El orden no es un campo: es la posición dentro del array.** Reordenar no cambia ningún `id`.
   Esto es lo que evita que un `DELETE` en vuelo acabe borrando otra tarea.
3. **El frontend no se compila.** Los archivos que sirve Spring son los archivos fuente: sin
   empaquetador, sin transpilar, sin `node_modules` en producción. Node solo se usa para comprobar
   tipos y ejecutar pruebas.
4. **No hay base de datos.** Se persiste en archivos JSON con escritura atómica.

## Estilo de código

### El valor de retorno pasa por una variable

Para que se pueda registrar o inspeccionar sin reescribir el método.

```java
// Bien
public Tarea consultarPorId(int id) {
	Tarea resultado = this.almacen.buscarPorId(id).orElseThrow(() -> new TareaNoEncontradaException(id));
	log.debug("consultarPorId({}) -> encontrada", id);
	return resultado;
}

// Mal
public Tarea consultarPorId(int id) {
	return this.almacen.buscarPorId(id).orElseThrow(() -> new TareaNoEncontradaException(id));
}
```

### Nada de código espagueti

**Si las dos ramas producen un valor, `if/else`, no `if` con `return`.**

```java
// Bien
String resultado;
if (texto == null) {
	resultado = "";
}
else {
	resultado = texto.strip();
}
return resultado;

// Mal
if (texto == null) {
	return "";
}
return texto.strip();
```

**Casos excluyentes: `if/else if/else` o `switch`, no una cadena de `return` sueltos.** Escritos como
una sola decisión se ve de un vistazo que uno y solo uno se aplica siempre.

```js
// Bien
if (parrafo.hidden) {
	boton.hidden = true;
}
else if (estaDesplegada) {
	boton.textContent = 'Ver menos';
}
else {
	boton.hidden = !desborda;
}

// Mal: tres if con return, que obliga a leerlos todos para saber que son excluyentes
```

**Un bucle no sale por el medio con `return`: acumula en una variable y corta por la condición.**

```java
// Bien
int resultado = POSICION_NO_ENCONTRADA;
for (int posicion = 0; posicion < tareas.size() && resultado == POSICION_NO_ENCONTRADA; posicion++) {
	if (tareas.get(posicion).id() == id) {
		resultado = posicion;
	}
}

// Mal: for con return dentro
```

**Excepción aceptada:** las guardas al principio de un manejador de eventos (`if (!boton) return;`).
Ahí el `else` anidaría el cuerpo entero de la función y se lee peor. Es la única excepción.

**Comprobar antes de mutar, nunca mutar y después decidir si hacía falta.** Lo segundo funciona, pero
obliga a razonar hacia atrás para convencerse de que el estado queda bien.

### Nombres

En español y descriptivos, también los privados y los de las pruebas: `buscarPosicionDeTarea`,
`descartarFondosHuerfanos`, `elPatronDeIconosEncuentraCadaEtiqueta`. Nada de `data`, `temp`, `aux`
ni abreviaturas. Un nombre que obliga a leer el cuerpo para saber qué hace está mal puesto.

### Comentarios

Explican **por qué**, no qué. Si un comentario repite lo que dice el código, sobra. Si una decisión
tenía una alternativa razonable que se descartó, se dice cuál y por qué. Un comentario que ya no
describe el código es peor que no tener ninguno: al tocar algo, se repasa su comentario.

### Tipos cualificados

Siempre por `import`, nunca `java.util.Collections.unmodifiableMap(...)` ni
`org.mockito.Mockito.never()` escritos enteros en medio del código.

## Arquitectura

### Servidor: tres capas, sin repository

```
TareasController  →  TareasService  →  AlmacenTareas / AlmacenFondos  →  ArchivoJsonAtomico
```

- **Controlador:** solo el camino feliz. **Ni un `try/catch`.**
- **Servicio:** lógica y validaciones de negocio; lanza excepciones de dominio.
- **Almacenes:** guardianes del estado compartido y de la E/S. No son repositories: no hay ORM ni
  base de datos.
- **`ManejadorErroresGlobal`:** único punto que traduce excepciones a HTTP, en formato
  `ProblemDetail` (RFC 9457). Añadir un endpoint no debe requerir escribir manejo de errores.

### Cómo se añade un dato asociado a la tarea

Es el patrón que ya siguen los fondos y que hay que repetir tal cual:

1. Un almacén propio que delega la E/S en `ArchivoJsonAtomico`, con su ruta en `PropiedadesAlmacen`
   y en `application.properties`.
2. Todos sus métodos `synchronized`, con `@PostConstruct cargarDesdeArchivo()`.
3. Endpoints propios (`GET /tarea/fondo`, `PUT /tarea/{id}/fondo`).
4. **Limpieza de huérfanos por dos vías:** olvidar la asociación al borrar la tarea
   (`TareasService.eliminar` llama a `fondos.olvidar(id)`) y descartar las que sobren al arrancar
   (`@PostConstruct descartarFondosHuerfanos` → `conservarSolo`).

`ArchivoJsonAtomico` ya resuelve la escritura en dos pasos, el respaldo para sistemas de archivos que
no admiten movimiento atómico y la limpieza del temporal cuando falla. **Reutilízalo, no lo copies:**
existe precisamente porque esa fontanería estaba duplicada y el mismo fallo aparecía en las dos
copias.

### Frontend: cada archivo con una responsabilidad

| Archivo | Responsabilidad exclusiva |
|---|---|
| `estado.js` | **Única fuente de verdad.** El DOM nunca se consulta para saber qué hay ni en qué orden |
| `vista.js` | Lo único que toca el DOM. No decide nada |
| `api.js` | Lo único que llama a `fetch`. Convierte todo fallo en `ErrorApi` |
| `preferencias.js` | Lo único que toca `localStorage`. Todo acceso dentro de `try/catch` |
| `arrastre.js` | SortableJS y la reordenación por teclado |
| `app.js` | Orquestador. El único que conoce a los demás |
| `tipos.js` | Solo `@typedef` compartidos. No se ejecuta en el navegador |

**Nunca se construye HTML concatenando cadenas.** Se clona la `<template>` del `index.html` y se
rellena con `textContent`, de modo que el texto del usuario jamás pueda interpretarse como marcado.

Los manejadores van **delegados en el contenedor**, uno por tipo de evento y no uno por tarjeta, así
que añadir o quitar tarjetas no implica montar ni desmontar escuchadores.

Toda mutación sigue el mismo guion: respaldo → cambio local → refresco mínimo → llamada a la API →
si falla, restaurar el respaldo y avisar.

Los elementos se buscan con `exigirElemento` / `exigirDentro`, que lanzan al cargar si falta algo. Un
`null` silencioso reventaría mucho más tarde y lejos de la causa.

## CSS

- **Especificidad plana:** una clase por selector. Sin ids, sin anidamiento, sin `>` encadenados.
- **BEM:** `bloque__elemento--modificador`.
- **Todo color, espacio y radio sale de una variable de `:root`.** No hay valores sueltos. La escala
  va de `--espacio-1` a `--espacio-6`, y los radios son `--radio` y `--radio-pequeno`: si algo no
  encaja, se calcula desde ellas con `calc()`, no se inventa un escalón nuevo.
- **Sin `!important`.** Hay una única excepción, la regla `[hidden]`, y está documentada en el
  archivo explicando por qué hace falta.
- **Mobile first.** Cortes en 600 px y 1024 px.
- **El tema se declara en tres bloques:** `:root` define la paleta clara completa;
  `@media (prefers-color-scheme: dark) :root:not([data-tema="claro"])` y `:root[data-tema="oscuro"]`
  solo redefinen variables. Ningún color se define únicamente dentro de un media query.
- **`prefers-reduced-motion` se respeta siempre.**

## Accesibilidad

No es un extra del proyecto, es una de sus señas. Lo que ya hay y no se puede perder:

- Cada botón de una tarjeta dice **de qué tarea es** con `aria-label`. Sin eso, un lector de pantalla
  recorre la lista diciendo «Borrar, botón» sin nombrar nunca la tarea.
- Región `aria-live` que anuncia los cambios; enlace de salto al listado; `aria-expanded` donde
  corresponde; iconos con `aria-hidden`.
- Los diálogos son `<dialog>` nativos con `showModal()`: traen el foco atrapado, el cierre con
  Escape y la devolución del foco al elemento que los abrió, sin ARIA escrita a mano.
- Gestión explícita del foco tras borrar, deshacer y reordenar.
- Contraste medido: texto ≥ 4.5:1 en los dos temas. **Si cambias un color, vuelve a medirlo.**
- Todo lo que se puede hacer con el ratón se puede hacer con el teclado.

## Pruebas

156 en total: 83 del servidor y 73 del navegador.

```bash
.\mvnw.cmd verify   # Java, más el informe de cobertura en target/site/jacoco
npm test            # frontend (Vitest)
npm run tipos       # comprobación de tipos, sin compilar nada
```

- Las del frontend viven en **`src/test/js/`, nunca en `static/`**: todo lo que hay bajo `static/` lo
  sirve Spring y acabaría publicado en internet.
- Las de `vista.js` cargan el **`index.html` real** en jsdom. Si renombras una clase de la plantilla,
  la prueba falla igual que fallaría la aplicación en el navegador.
- **Una prueba tiene que poder fallar.** Al escribir una de regresión, reintroduce el fallo y
  comprueba que salta; si no salta, no está probando nada. Ya hubo una que contaba etiquetas con
  holgura y pasaba en verde con el fallo dentro.
- No hay umbral de cobertura a propósito: perseguir un porcentaje lleva a escribir pruebas vacías.

## Comandos

```bash
.\mvnw.cmd spring-boot:run                                    # arrancar
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=demo"
.\mvnw.cmd clean verify                                       # compilar y probar
npm test ; npm run tipos                                      # frontend
docker build -t tareas . ; docker run --rm -p 8080:8080 tareas
```

`mvn` no está en el PATH: siempre `./mvnw` o `.\mvnw.cmd`.

La documentación de la API se sirve en `/swagger-ui.html` y el esquema en `/v3/api-docs`.

## Trampas de este entorno (aprendidas a base de fallar)

- **PowerShell 5.1**, no 7. `Set-Content -Encoding utf8NoBOM` **no existe**; usa
  `[IO.File]::WriteAllText($ruta, $texto)`, que escribe UTF-8 sin BOM.
- **Los acentos se corrompen** al pasarlos a git como argumento. Los mensajes de commit van con
  `git commit -F archivo`, nunca con `-m` si llevan tildes.
- **Jackson 3:** `tools.jackson.databind` y `tools.jackson.core`, pero **las anotaciones siguen en
  `com.fasterxml.jackson.annotation`**. Mezclarlo no compila.
- **Spring Boot 4:** el starter es `spring-boot-starter-webmvc`, no `-web`. Para pruebas,
  `spring-boot-starter-webmvc-test`. springdoc tiene que ser de la línea **3.x**; la 2.x apunta a
  Boot 3.
- **En surefire, `@{argLine}` es un idioma de JaCoCo.** Aquí conviven el agente de JaCoCo y el de
  Mockito; no toques esa línea sin entender qué hace cada parte.
- **En `jsconfig.json`, declarar `exclude` anula el valor por defecto**, que ya traía `node_modules`.
  Por eso están puestos `node_modules` y `maxNodeModuleJsDepth: 0`.
- **No edites archivos desde la web de GitHub** si tienes trabajo local sin subir: cada cambio en la
  web es un commit que solo existe allí y obliga a rebasar antes de poder empujar.

## Git

Los commits los hace el usuario. **No commitees ni hagas push**, y no aparezcas como coautor.
Cuando termines, entrega el mensaje de commit preparado para que lo ejecute él.

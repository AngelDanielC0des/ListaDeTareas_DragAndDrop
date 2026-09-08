package angel.xtd.tareas.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import angel.xtd.tareas.dto.ActualizarTareaPeticion;
import angel.xtd.tareas.dto.CambiarCompletadaPeticion;
import angel.xtd.tareas.dto.ConfiguracionTareas;
import angel.xtd.tareas.dto.CambiarFondoPeticion;
import angel.xtd.tareas.dto.CrearTareaPeticion;
import angel.xtd.tareas.dto.CambiarGrupoPeticion;
import angel.xtd.tareas.dto.CrearGrupoPeticion;
import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.dto.Grupo;
import angel.xtd.tareas.dto.GruposConAsignaciones;
import angel.xtd.tareas.dto.ReordenarGruposPeticion;
import angel.xtd.tareas.dto.ReordenarPeticion;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.service.TareasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;

/**
 * API REST de la lista de tareas.
 *
 * <p>Solo escribe el camino feliz: no hay un solo {@code try/catch}. Las validaciones de formato las
 * dispara {@code @Valid} sobre los DTO y las de negocio el servicio; ambas acaban en el manejador
 * global de errores, que es el único sitio que traduce excepciones a códigos HTTP.
 */
@RestController
@RequestMapping("/tarea")
@Tag(name = "Tareas", description = "Alta, consulta, edición, borrado, reordenación y fondo de las tareas")
public class TareasController {

	/*
	 * Sobre las anotaciones de OpenAPI que salpican esta clase: se limitan a poner el resumen que
	 * verá quien lea la documentación y a declarar los códigos que springdoc no puede adivinar.
	 * Todo lo demás —los tipos, los parámetros, qué campos son obligatorios— lo deduce él solo de
	 * las anotaciones de Spring y de las de validación, así que no hay un segundo documento que
	 * pueda quedarse desactualizado respecto al código.
	 */

	private static final Logger log = LoggerFactory.getLogger(TareasController.class);

	private final TareasService servicio;

	public TareasController(TareasService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
	@Operation(summary = "Listar las tareas",
			description = "Devuelve todas las tareas en su orden actual. El orden es la posición en el array.")
	public List<Tarea> consultarTodas() {
		List<Tarea> resultado = this.servicio.consultarTodas();
		log.debug("GET /tarea -> 200 con {} tareas", resultado.size());
		return resultado;
	}

	/**
	 * Límites del servidor que el navegador necesita para configurarse solo.
	 *
	 * <p>Evita que el 280 esté escrito a mano en el HTML y en el JavaScript: el frontend pide esto al
	 * arrancar y rellena con ello los {@code maxlength} y el contador de caracteres.
	 */
	@GetMapping("/configuracion")
	@Operation(summary = "Consultar los límites del servidor",
			description = "El navegador lo pide al arrancar para rellenar el maxlength y el contador de "
					+ "caracteres, en vez de llevar el número escrito a mano.")
	public ConfiguracionTareas consultarConfiguracion() {
		ConfiguracionTareas resultado = ConfiguracionTareas.porDefecto();
		log.debug("GET /tarea/configuracion -> {}", resultado);
		return resultado;
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consultar una tarea")
	@ApiResponse(responseCode = "200", description = "La tarea existe")
	@ApiResponse(responseCode = "404", description = "No hay ninguna tarea con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public Tarea consultarPorId(@PathVariable int id) {
		Tarea resultado = this.servicio.consultarPorId(id);
		log.debug("GET /tarea/{} -> 200", id);
		return resultado;
	}

	@PostMapping
	@Operation(summary = "Crear una tarea",
			description = "Se añade al final de la lista. El id lo asigna el servidor y una tarea recién "
					+ "creada nunca está completada, así que ninguno de los dos se acepta del cliente.")
	@ApiResponse(responseCode = "201", description = "Creada; la cabecera Location apunta a la tarea nueva")
	@ApiResponse(responseCode = "400", description = "El texto está vacío o supera el límite de caracteres",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<Tarea> crear(@Valid @RequestBody CrearTareaPeticion peticion) {
		Tarea creada = this.servicio.crear(peticion.texto());
		ResponseEntity<Tarea> resultado = ResponseEntity.created(URI.create("/tarea/" + creada.id())).body(creada);
		log.info("POST /tarea -> 201 id={}", creada.id());
		return resultado;
	}

	@PutMapping("/{id}")
	@Operation(summary = "Actualizar el texto y el estado de una tarea",
			description = "Conserva el id y, sobre todo, la posición en la lista.")
	@ApiResponse(responseCode = "200", description = "Actualizada")
	@ApiResponse(responseCode = "400", description = "Falta algún campo o el texto no es válido",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "No hay ninguna tarea con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public Tarea actualizar(@PathVariable int id, @Valid @RequestBody ActualizarTareaPeticion peticion) {
		Tarea resultado = this.servicio.actualizar(id, peticion.texto(), peticion.completada());
		log.info("PUT /tarea/{} -> 200", id);
		return resultado;
	}

	/**
	 * Marcar la casilla no debería obligar a reenviar el texto: así el toggle no puede pisar una
	 * edición de texto que el usuario tuviera a medias.
	 */
	@PatchMapping("/{id}/completada")
	@Operation(summary = "Marcar o desmarcar una tarea",
			description = "Existe aparte del PUT para no obligar a reenviar el texto: así marcar la casilla "
					+ "no puede pisar una edición que el usuario tuviera a medias.")
	@ApiResponse(responseCode = "200", description = "Actualizada")
	@ApiResponse(responseCode = "400", description = "Falta el campo completada",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "No hay ninguna tarea con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public Tarea cambiarCompletada(@PathVariable int id, @Valid @RequestBody CambiarCompletadaPeticion peticion) {
		Tarea resultado = this.servicio.cambiarCompletada(id, peticion.completada());
		log.info("PATCH /tarea/{}/completada -> 200 ({})", id, peticion.completada());
		return resultado;
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Borrar una tarea",
			description = "Borra también el fondo que tuviera asignado, para que no queden asociaciones "
					+ "de tareas que ya no existen.")
	@ApiResponse(responseCode = "204", description = "Borrada; no se devuelve cuerpo")
	@ApiResponse(responseCode = "404", description = "No hay ninguna tarea con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public void eliminar(@PathVariable int id) {
		this.servicio.eliminar(id);
		log.info("DELETE /tarea/{} -> 204", id);
	}

	/**
	 * Devuelve qué fondo tiene cada tarea, en una sola petición.
	 *
	 * <p>Va aparte de {@code GET /tarea} para no tocar la forma de la tarea, que sigue teniendo
	 * exactamente tres campos. El navegador pide las dos cosas al arrancar y las cruza por el id.
	 */
	@GetMapping("/fondo")
	@Operation(summary = "Consultar el fondo de cada tarea",
			description = "Solo aparecen las tareas que tienen uno. Va aparte de GET /tarea para no tocar "
					+ "la forma de la tarea, que sigue teniendo exactamente tres campos.")
	public Map<Integer, Fondo> consultarFondos() {
		Map<Integer, Fondo> resultado = this.servicio.consultarFondos();
		log.debug("GET /tarea/fondo -> {} tareas con fondo", resultado.size());
		return resultado;
	}

	@PutMapping("/{id}/fondo")
	@Operation(summary = "Cambiar el fondo de una tarea",
			description = "Se manda «ninguno» para quitarlo. Devuelve el mapa completo ya actualizado, para "
					+ "que el cliente no tenga que recomponerlo.")
	@ApiResponse(responseCode = "200", description = "Asignado; se devuelve el mapa completo")
	@ApiResponse(responseCode = "400", description = "El fondo no es uno de los valores admitidos",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "No hay ninguna tarea con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public Map<Integer, Fondo> cambiarFondo(@PathVariable int id, @Valid @RequestBody CambiarFondoPeticion peticion) {
		this.servicio.cambiarFondo(id, peticion.fondo());
		Map<Integer, Fondo> resultado = this.servicio.consultarFondos();
		log.info("PUT /tarea/{}/fondo -> 200 ({})", id, peticion.fondo());
		return resultado;
	}

	/**
	 * Endpoint del drag &amp; drop: una sola petición atómica con todos los ids en el nuevo orden.
	 *
	 * <p>El segmento literal {@code /orden} tiene prioridad sobre la plantilla {@code /{id}} en el
	 * emparejamiento de rutas de Spring, así que no hay ambigüedad con {@code PUT /tarea/{id}}.
	 */
	/* ------------------------------------------------------------------ Grupos */

	/*
	 * Los grupos cuelgan de /tarea aunque sean otro recurso, porque el enunciado pide un solo
	 * controlador. No hay ambigüedad de rutas: /tarea/grupo/{id} tiene tres segmentos y /tarea/{id}
	 * dos, y el literal /tarea/grupo/orden gana a la plantilla /tarea/grupo/{id}.
	 */

	@GetMapping("/grupo")
	@Operation(summary = "Consultar los grupos y a qué grupo pertenece cada tarea",
			description = "Van juntos en una sola petición porque por separado no sirven de nada: los grupos "
					+ "sin saber quién pertenece a cuál no permiten pintar, y al revés tampoco.")
	public GruposConAsignaciones consultarGrupos() {
		GruposConAsignaciones resultado = this.servicio.consultarGrupos();
		log.debug("GET /tarea/grupo -> {} grupos", resultado.grupos().size());
		return resultado;
	}

	@PostMapping("/grupo")
	@Operation(summary = "Crear un grupo", description = "Se añade al final. El id lo asigna el servidor.")
	@ApiResponse(responseCode = "201", description = "Creado; la cabecera Location apunta al grupo nuevo")
	@ApiResponse(responseCode = "400", description = "El nombre está vacío o supera el límite",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<Grupo> crearGrupo(@Valid @RequestBody CrearGrupoPeticion peticion) {
		Grupo creado = this.servicio.crearGrupo(peticion.nombre());
		ResponseEntity<Grupo> resultado = ResponseEntity.created(URI.create("/tarea/grupo/" + creado.id()))
			.body(creado);
		log.info("POST /tarea/grupo -> 201 id={}", creado.id());
		return resultado;
	}

	@PutMapping("/grupo/{idGrupo}")
	@Operation(summary = "Renombrar un grupo", description = "Conserva el id y la posición.")
	@ApiResponse(responseCode = "200", description = "Renombrado")
	@ApiResponse(responseCode = "400", description = "El nombre está vacío o supera el límite",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "No hay ningún grupo con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public Grupo renombrarGrupo(@PathVariable int idGrupo, @Valid @RequestBody CrearGrupoPeticion peticion) {
		Grupo resultado = this.servicio.renombrarGrupo(idGrupo, peticion.nombre());
		log.info("PUT /tarea/grupo/{} -> 200", idGrupo);
		return resultado;
	}

	@DeleteMapping("/grupo/{idGrupo}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Borrar un grupo",
			description = "**Sus tareas no se borran**: se quedan sueltas, fuera de toda sección. Un grupo "
					+ "es una forma de ordenar lo que hay, no un contenedor del que las tareas dependan.")
	@ApiResponse(responseCode = "204", description = "Borrado; no se devuelve cuerpo")
	@ApiResponse(responseCode = "404", description = "No hay ningún grupo con ese id",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public void eliminarGrupo(@PathVariable int idGrupo) {
		this.servicio.eliminarGrupo(idGrupo);
		log.info("DELETE /tarea/grupo/{} -> 204", idGrupo);
	}

	@PutMapping("/grupo/orden")
	@Operation(summary = "Reordenar los grupos",
			description = "Recibe todos los ids de grupo en el orden deseado. Ningún id cambia: lo que se "
					+ "mueve es la posición.")
	@ApiResponse(responseCode = "200", description = "Aplicado; se devuelven los grupos en su nuevo orden")
	@ApiResponse(responseCode = "400", description = "La lista de ids viene vacía",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Los ids no son una permutación exacta de los grupos "
			+ "que existen",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public List<Grupo> reordenarGrupos(@Valid @RequestBody ReordenarGruposPeticion peticion) {
		List<Grupo> resultado = this.servicio.reordenarGrupos(peticion.ids());
		log.info("PUT /tarea/grupo/orden -> 200 con {} grupos", resultado.size());
		return resultado;
	}

	@PutMapping("/{id}/grupo")
	@Operation(summary = "Meter una tarea en un grupo",
			description = "Se manda `null` para dejarla suelta. Devuelve el estado completo de los grupos "
					+ "ya actualizado, para que el cliente no tenga que recomponerlo.")
	@ApiResponse(responseCode = "200", description = "Asignada; se devuelven grupos y asignaciones")
	@ApiResponse(responseCode = "404", description = "No existe la tarea, o no existe el grupo indicado",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public GruposConAsignaciones cambiarGrupo(@PathVariable int id, @RequestBody CambiarGrupoPeticion peticion) {
		GruposConAsignaciones resultado = this.servicio.cambiarGrupo(id, peticion.grupo());
		log.info("PUT /tarea/{}/grupo -> 200 ({})", id, peticion.grupo());
		return resultado;
	}

	/* ------------------------------------------------------------------- Orden */

	@PutMapping("/orden")
	@Operation(summary = "Reordenar todas las tareas",
			description = "Recibe todos los ids en el orden deseado. **Ningún id cambia**: lo que se mueve "
					+ "es la posición. Se manda la lista entera, y no «mueve X a la posición N», para que la "
					+ "operación sea idempotente y el servidor pueda comprobar que no se pierde ninguna tarea.")
	@ApiResponse(responseCode = "200", description = "Aplicado; se devuelve la lista en su nuevo orden")
	@ApiResponse(responseCode = "400", description = "La lista de ids viene vacía",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Los ids no son una permutación exacta de las tareas "
			+ "que existen: sobra alguno, falta alguno o hay repetidos",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
	public List<Tarea> reordenar(@Valid @RequestBody ReordenarPeticion peticion) {
		List<Tarea> resultado = this.servicio.reordenar(peticion.ids());
		log.info("PUT /tarea/orden -> 200 con {} tareas", resultado.size());
		return resultado;
	}

}

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
import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.dto.ReordenarPeticion;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.service.TareasService;
import jakarta.validation.Valid;

/**
 * API REST de la lista de tareas.
 *
 * <p>Solo escribe el camino feliz: no hay un solo {@code try/catch}. Las validaciones de formato las
 * dispara {@code @Valid} sobre los DTO y las de negocio el servicio; ambas acaban en el manejador
 * global de errores, que es el único sitio que traduce excepciones a códigos HTTP.
 */
@RestController
@RequestMapping("/tarea")
public class TareasController {

	private static final Logger log = LoggerFactory.getLogger(TareasController.class);

	private final TareasService servicio;

	public TareasController(TareasService servicio) {
		this.servicio = servicio;
	}

	@GetMapping
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
	public ConfiguracionTareas consultarConfiguracion() {
		ConfiguracionTareas resultado = ConfiguracionTareas.porDefecto();
		log.debug("GET /tarea/configuracion -> {}", resultado);
		return resultado;
	}

	@GetMapping("/{id}")
	public Tarea consultarPorId(@PathVariable int id) {
		Tarea resultado = this.servicio.consultarPorId(id);
		log.debug("GET /tarea/{} -> 200", id);
		return resultado;
	}

	@PostMapping
	public ResponseEntity<Tarea> crear(@Valid @RequestBody CrearTareaPeticion peticion) {
		Tarea creada = this.servicio.crear(peticion.texto());
		ResponseEntity<Tarea> resultado = ResponseEntity.created(URI.create("/tarea/" + creada.id())).body(creada);
		log.info("POST /tarea -> 201 id={}", creada.id());
		return resultado;
	}

	@PutMapping("/{id}")
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
	public Tarea cambiarCompletada(@PathVariable int id, @Valid @RequestBody CambiarCompletadaPeticion peticion) {
		Tarea resultado = this.servicio.cambiarCompletada(id, peticion.completada());
		log.info("PATCH /tarea/{}/completada -> 200 ({})", id, peticion.completada());
		return resultado;
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
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
	public Map<Integer, Fondo> consultarFondos() {
		Map<Integer, Fondo> resultado = this.servicio.consultarFondos();
		log.debug("GET /tarea/fondo -> {} tareas con fondo", resultado.size());
		return resultado;
	}

	@PutMapping("/{id}/fondo")
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
	@PutMapping("/orden")
	public List<Tarea> reordenar(@Valid @RequestBody ReordenarPeticion peticion) {
		List<Tarea> resultado = this.servicio.reordenar(peticion.ids());
		log.info("PUT /tarea/orden -> 200 con {} tareas", resultado.size());
		return resultado;
	}

}

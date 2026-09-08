package angel.xtd.tareas.controller;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import angel.xtd.tareas.dto.ActualizarTareaPeticion;
import angel.xtd.tareas.dto.CrearTareaPeticion;
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

	/** Sirve tanto para editar el texto como para marcar o desmarcar la casilla. */
	@PutMapping("/{id}")
	public Tarea actualizar(@PathVariable int id, @Valid @RequestBody ActualizarTareaPeticion peticion) {
		Tarea resultado = this.servicio.actualizar(id, peticion.texto(), peticion.completada());
		log.info("PUT /tarea/{} -> 200", id);
		return resultado;
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void eliminar(@PathVariable int id) {
		this.servicio.eliminar(id);
		log.info("DELETE /tarea/{} -> 204", id);
	}

}

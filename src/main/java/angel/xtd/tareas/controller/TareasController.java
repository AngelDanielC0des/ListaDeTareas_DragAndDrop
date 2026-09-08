package angel.xtd.tareas.controller;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import angel.xtd.tareas.dto.ActualizarTareaPeticion;
import angel.xtd.tareas.dto.CrearTareaPeticion;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.service.TareasService;
import jakarta.validation.Valid;

/**
 * API REST de la lista de tareas: recibe y devuelve JSON.
 *
 * <p>Cinco operaciones sobre {@code /tarea}: listar, consultar una, crear, modificar y borrar.
 *
 * <p>El «no existe» llega desde el servicio como un {@code Optional} vacío o un {@code false}, y se
 * traduce aquí a un {@code 404} con {@link ResponseEntity}. Por eso esta versión no necesita
 * excepciones propias ni un manejador global de errores.
 *
 * <p><b>Nota sobre los 405 y 415.</b> Al no haber ningún {@code @ControllerAdvice} en esta versión,
 * de las excepciones del framework se encarga Spring Boot y responde los códigos correctos. Si en el
 * futuro se añade un manejador propio, <b>no</b> debe llevar un {@code @ExceptionHandler(Exception)}
 * con prioridad alta: Spring se queda con el primer advice que tenga cualquier método aplicable, y
 * {@code Exception} casa con todo, así que dejaría sin ejecutar al de Boot y convertiría esos 405 y
 * 415 en 500. Hay tests que lo cubren.
 *
 * <p>Los textos de esas respuestas del framework se traducen al español en
 * {@code messages.properties}; sin ese archivo saldrían en inglés.
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
	public ResponseEntity<Tarea> consultarPorId(@PathVariable int id) {
		ResponseEntity<Tarea> resultado = this.servicio.buscarPorId(id)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
		log.debug("GET /tarea/{} -> {}", id, resultado.getStatusCode().value());
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
	public ResponseEntity<Tarea> actualizar(@PathVariable int id, @Valid @RequestBody ActualizarTareaPeticion peticion) {
		ResponseEntity<Tarea> resultado = this.servicio.actualizar(id, peticion.texto(), peticion.completada())
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
		log.info("PUT /tarea/{} -> {}", id, resultado.getStatusCode().value());
		return resultado;
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> eliminar(@PathVariable int id) {
		ResponseEntity<Void> resultado = this.servicio.eliminar(id)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
		log.info("DELETE /tarea/{} -> {}", id, resultado.getStatusCode().value());
		return resultado;
	}

}

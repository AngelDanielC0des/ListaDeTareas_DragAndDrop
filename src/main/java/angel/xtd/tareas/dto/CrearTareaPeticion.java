package angel.xtd.tareas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /tarea}.
 *
 * <p>No lleva {@code id} ni {@code completada}: el id lo asigna el servidor y una tarea recién
 * creada nunca está completada. Aceptar esos campos del cliente sería dejarle decidir la identidad.
 */
public record CrearTareaPeticion(

		@NotBlank(message = "El texto de la tarea no puede estar vacío")
		@Size(max = Tarea.MAX_CARACTERES_TEXTO, message = "El texto no puede superar los {max} caracteres")
		String texto) {

}

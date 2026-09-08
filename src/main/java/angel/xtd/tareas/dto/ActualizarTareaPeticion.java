package angel.xtd.tareas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PUT /tarea/{id}}.
 *
 * <p>El id viaja en la ruta, no en el cuerpo, para que no puedan contradecirse.
 * {@code completada} es {@code Boolean} y no {@code boolean} a propósito: así {@code @NotNull}
 * distingue «el cliente ha mandado false» de «el cliente se ha olvidado del campo», que con el
 * primitivo serían indistinguibles.
 */
public record ActualizarTareaPeticion(

		@NotBlank(message = "El texto de la tarea no puede estar vacío")
		@Size(max = Tarea.MAX_CARACTERES_TEXTO, message = "El texto no puede superar los {max} caracteres")
		String texto,

		@NotNull(message = "Hay que indicar si la tarea está completada")
		Boolean completada) {

}

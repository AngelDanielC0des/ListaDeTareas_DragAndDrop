package angel.xtd.tareas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de {@code PUT /tarea/{id}/fondo}.
 *
 * <p>Para quitar el fondo se manda {@code "ninguno"}, no un {@code null}: así el cliente siempre
 * dice explícitamente qué quiere y no hay que distinguir «quitar el fondo» de «se me ha olvidado el
 * campo».
 */
public record CambiarFondoPeticion(

		@Schema(description = "Uno de los valores del enum. «ninguno» quita el fondo.", example = "ondas")
		@NotNull(message = "Hay que indicar el fondo")
		Fondo fondo) {

}

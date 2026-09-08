package angel.xtd.tareas.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * Cuerpo de {@code PUT /tarea/orden}: los ids de todas las tareas, en el orden deseado.
 *
 * <p>Se manda la lista completa y no «mueve la tarea X a la posición N» porque así la operación es
 * idempotente y atómica: el servidor no tiene que reconstruir el estado previo del cliente, se
 * limita a comprobar que lo recibido es una permutación exacta de lo que tiene.
 */
public record ReordenarPeticion(

		@Schema(description = "Todos los ids que existen, en el orden deseado", example = "[3, 1, 2]")
		@NotEmpty(message = "Hay que indicar el orden de las tareas")
		List<Integer> ids) {

}

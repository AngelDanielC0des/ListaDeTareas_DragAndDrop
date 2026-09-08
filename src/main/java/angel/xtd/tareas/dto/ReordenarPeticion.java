package angel.xtd.tareas.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * Cuerpo de {@code PUT /tarea/orden}: los ids de todas las tareas, en el orden deseado.
 *
 * <p>Se manda la lista completa y no «mueve la tarea X a la posición N» porque así la operación es
 * idempotente y atómica: el servidor no tiene que reconstruir el estado previo del cliente, se
 * limita a comprobar que lo recibido es una permutación exacta de lo que tiene.
 */
public record ReordenarPeticion(

		@NotEmpty(message = "Hay que indicar el orden de las tareas")
		List<Integer> ids) {

}

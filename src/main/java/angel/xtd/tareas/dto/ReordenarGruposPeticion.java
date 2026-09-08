package angel.xtd.tareas.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * Cuerpo de {@code PUT /tarea/grupo/orden}: los ids de todos los grupos, en el orden deseado.
 *
 * <p>Mismo criterio que al reordenar tareas: se manda la lista completa y no «mueve el grupo X a la
 * posición N», para que la operación sea idempotente y el servidor pueda comprobar que no se pierde
 * ninguno por el camino.
 */
public record ReordenarGruposPeticion(

		@Schema(description = "Todos los ids de grupo que existen, en el orden deseado", example = "[2, 1]")
		@NotEmpty(message = "Hay que indicar el orden de los grupos")
		List<Integer> ids) {

}

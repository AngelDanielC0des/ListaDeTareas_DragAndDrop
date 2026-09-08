package angel.xtd.tareas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo de {@code PUT /tarea/{id}/grupo}.
 *
 * <p>A diferencia del fondo, aquí <b>sí</b> se usa {@code null} para decir «ninguno»: un grupo se
 * identifica por su id y no hay ningún valor natural que signifique «sin grupo», mientras que los
 * fondos son un enum donde cabía añadir un {@code NINGUNO} explícito.
 *
 * @param grupo id del grupo, o {@code null} para sacar la tarea de donde esté
 */
public record CambiarGrupoPeticion(

		@Schema(description = "Id del grupo, o null para dejar la tarea suelta", example = "1")
		Integer grupo) {

}

package angel.xtd.tareas.dto;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Todo lo que el navegador necesita saber sobre los grupos, en una sola petición.
 *
 * <p>Van juntos porque por separado no sirven de nada: los grupos sin saber quién pertenece a cuál
 * no permiten pintar nada, y las asignaciones sin los nombres tampoco. Pedirlos en dos peticiones
 * abriría además una ventana en la que el navegador tendría una mitad nueva y la otra vieja.
 *
 * @param grupos en el orden en que deben pintarse
 * @param asignaciones id de tarea al id de su grupo; solo aparecen las tareas que están en alguno
 */
public record GruposConAsignaciones(

		@Schema(description = "Los grupos, en su orden")
		List<Grupo> grupos,

		@Schema(description = "Id de tarea al id de su grupo. Las tareas sueltas no aparecen.",
				example = "{\"3\": 1, \"7\": 1}")
		Map<Integer, Integer> asignaciones) {

}

package angel.xtd.tareas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /tarea/grupo} y de {@code PUT /tarea/grupo/{id}}. */
public record CrearGrupoPeticion(

		@Schema(description = "Nombre del grupo", example = "Mañana")
		@NotBlank(message = "El nombre del grupo no puede estar vacío")
		@Size(max = Grupo.MAX_CARACTERES_NOMBRE, message = "El nombre no puede superar los {max} caracteres")
		String nombre) {

}

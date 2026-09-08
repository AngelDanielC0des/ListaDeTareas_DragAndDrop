package angel.xtd.tareas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un grupo bajo el que se agrupan tareas, por ejemplo «Mañana» o «Casa».
 *
 * <p><b>El grupo no es un campo de la tarea, y no puede serlo:</b> una tarea son exactamente
 * {@code id}, {@code texto} y {@code completada}. La pertenencia se guarda aparte, igual que el
 * fondo, en {@code AlmacenGrupos}. Aquí solo vive la identidad del grupo y su nombre.
 *
 * <p>Como con las tareas, <b>el orden no es un campo</b>: es la posición dentro de la lista.
 */
public record Grupo(

		@Schema(description = "Identidad del grupo. No cambia nunca, ni al reordenar.", example = "1")
		int id,

		@Schema(description = "Nombre que se enseña en la cabecera de la sección.", example = "Mañana")
		String nombre) {

	/** Longitud máxima del nombre. Cabe en una cabecera sin partirse en dos líneas. */
	public static final int MAX_CARACTERES_NOMBRE = 60;

	/** Primer id que se reparte cuando no hay ningún grupo. */
	public static final int PRIMER_ID = 1;

	public Grupo conNombre(String nuevoNombre) {
		Grupo resultado = new Grupo(this.id, nuevoNombre);
		return resultado;
	}

}

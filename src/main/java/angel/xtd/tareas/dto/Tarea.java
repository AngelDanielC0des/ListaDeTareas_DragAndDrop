package angel.xtd.tareas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una tarea de la lista.
 *
 * <p>Solo tiene tres campos, y son los tres que se persisten en el JSON. <b>El orden NO es un campo
 * de la tarea</b>: viene dado por la posición dentro del array, así que mover una tarea no altera
 * ningún id. El {@code id} es identidad pura y no cambia nunca.
 *
 * <p>Es un {@code record}, es decir inmutable: modificar una tarea significa crear otra
 * ({@link #conCompletada}), lo que elimina de raíz los errores por estado compartido entre hilos.
 */
public record Tarea(

		@Schema(description = "Identidad de la tarea. No cambia nunca, ni siquiera al reordenar.", example = "1")
		int id,

		@Schema(description = "Lo que escribió el usuario.", example = "Comprar pan")
		String texto,

		@Schema(description = "Si ya está hecha.", example = "false")
		boolean completada) {

	/**
	 * Longitud máxima del texto.
	 *
	 * <p>Es la única fuente de verdad de todo el proyecto: la usan los DTO de petición para validar y
	 * el endpoint de configuración para que el navegador rellene los {@code maxlength} y el contador.
	 * El frontend NO lleva este número escrito en ningún sitio, precisamente para que cambiarlo aquí
	 * baste.
	 */
	public static final int MAX_CARACTERES_TEXTO = 280;

	/** Primer id que se reparte cuando la lista está vacía. */
	public static final int PRIMER_ID = 1;

	public Tarea conCompletada(boolean nuevoEstado) {
		Tarea resultado = new Tarea(this.id, this.texto, nuevoEstado);
		return resultado;
	}

}

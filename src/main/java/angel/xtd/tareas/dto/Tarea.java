package angel.xtd.tareas.dto;

/**
 * Una tarea de la lista.
 *
 * <p>Solo tiene tres campos, y son los tres que se persisten en el JSON. <b>El orden NO es un campo
 * de la tarea</b>: viene dado por la posición dentro del array. El {@code id} es identidad pura y no
 * cambia nunca.
 *
 * <p>Es un {@code record}, es decir inmutable: modificar una tarea significa crear otra, lo que
 * elimina de raíz los errores por estado compartido entre hilos.
 */
public record Tarea(int id, String texto, boolean completada) {

	/**
	 * Longitud máxima del texto, usada por los DTO de petición para validar.
	 *
	 * <p><b>Ojo:</b> este número está repetido en el {@code maxlength} de {@code index.html}.
	 * Mantenerlo en un solo sitio exigiría un endpoint de configuración que el navegador consultase
	 * al arrancar; aquí se ha preferido la duplicación a cambio de un endpoint menos.
	 *
	 * <p>La duplicación no queda sin red: {@code RecursosEstaticosTest} compara los dos números y
	 * falla si alguien cambia uno y se olvida del otro.
	 */
	public static final int MAX_CARACTERES_TEXTO = 280;

	/** Primer id que se reparte cuando la lista está vacía. */
	public static final int PRIMER_ID = 1;

}

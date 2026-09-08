package angel.xtd.tareas.error;

/**
 * El orden recibido en {@code PUT /tarea/orden} no es una permutación exacta de las tareas
 * existentes. El manejador global la traduce a {@code 409 Conflict}.
 *
 * <p>Es {@code 409} y no {@code 400} porque el cuerpo está bien formado: el problema es que choca
 * con el estado actual del servidor, normalmente porque el cliente tenía una lista desactualizada.
 */
public class OrdenInvalidoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public OrdenInvalidoException(String mensaje) {
		super(mensaje);
	}

}

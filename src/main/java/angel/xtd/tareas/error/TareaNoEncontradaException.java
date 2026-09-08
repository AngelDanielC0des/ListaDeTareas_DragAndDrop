package angel.xtd.tareas.error;

/**
 * La tarea pedida no existe. El manejador global la traduce a {@code 404}.
 *
 * <p>Es de dominio, no de HTTP: el servicio no sabe nada de códigos de estado, solo dice qué ha
 * pasado. La traducción a HTTP vive en un único sitio ({@link ManejadorErroresGlobal}).
 */
public class TareaNoEncontradaException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int id;

	public TareaNoEncontradaException(int id) {
		super("No existe ninguna tarea con id " + id);
		this.id = id;
	}

	public int getId() {
		return this.id;
	}

}

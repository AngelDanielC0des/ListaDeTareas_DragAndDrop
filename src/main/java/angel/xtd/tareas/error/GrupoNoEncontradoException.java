package angel.xtd.tareas.error;

/**
 * No existe ningún grupo con ese id. El manejador global la traduce a {@code 404}.
 *
 * <p>Lleva el id como campo, y no solo dentro del mensaje, para que el manejador pueda añadirlo como
 * propiedad del {@code ProblemDetail} y el cliente no tenga que sacarlo de un texto.
 */
public class GrupoNoEncontradoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int id;

	public GrupoNoEncontradoException(int id) {
		super("No existe ningún grupo con id " + id);
		this.id = id;
	}

	public int getId() {
		int resultado = this.id;
		return resultado;
	}

}

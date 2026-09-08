package angel.xtd.tareas.error;

/**
 * Fallo al leer o escribir el archivo de tareas. El manejador global la traduce a {@code 500}.
 *
 * <p>Envuelve las excepciones de E/S y de Jackson para que ni el servicio ni el controlador tengan
 * que saber cómo está implementada la persistencia.
 */
public class AlmacenamientoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AlmacenamientoException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}

}

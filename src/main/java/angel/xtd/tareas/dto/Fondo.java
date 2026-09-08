package angel.xtd.tareas.dto;

// Ojo con el paquete: Jackson 3 movió databind y core a tools.jackson, pero las anotaciones se
// quedaron en com.fasterxml.jackson.annotation por compatibilidad. Mezclarlos no compila.
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Fondo opcional de una tarjeta.
 *
 * <p>Es un {@code enum} y no un {@code String} libre para que el conjunto de valores válidos viva en
 * un solo sitio: el servidor no puede guardar un fondo que el navegador no sepa pintar.
 *
 * <p>Viaja en JSON en minúsculas ({@code "ondas"}) porque es lo natural en una API, mientras que en
 * Java las constantes van en mayúsculas como manda la convención.
 */
public enum Fondo {

	NINGUNO,
	ONDAS,
	PUNTOS,
	LINEAS,
	PAPEL,
	AURORA;

	@JsonValue
	public String valorJson() {
		String resultado = name().toLowerCase(Locale.ROOT);
		return resultado;
	}

	/**
	 * Convierte el valor recibido en JSON.
	 *
	 * <p>Un valor desconocido lanza {@link IllegalArgumentException}, que Jackson envuelve y el
	 * manejador de errores traduce a un {@code 400}: es lo correcto, porque el cliente ha mandado
	 * algo que no existe.
	 */
	@JsonCreator
	public static Fondo desdeJson(String valor) {
		Fondo resultado = valueOf(valor.toUpperCase(Locale.ROOT));
		return resultado;
	}

}

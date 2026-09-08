package angel.xtd.tareas.dto;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Ojo con el paquete: Jackson 3 movió databind y core a tools.jackson, pero las anotaciones se
// quedaron en com.fasterxml.jackson.annotation por compatibilidad. Mezclarlos no compila.
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

	/**
	 * El cambio de caja va siempre con {@link Locale#ROOT}, nunca con el idioma del sistema: en turco
	 * la {@code i} se convierte en {@code İ}, así que {@code LINEAS} se escribiría {@code "lİneas"} en
	 * el archivo de fondos y ese archivo ya no se podría volver a leer nunca.
	 */
	@JsonValue
	public String valorJson() {
		String resultado = name().toLowerCase(Locale.ROOT);
		return resultado;
	}

	/**
	 * Convierte el valor recibido en JSON.
	 *
	 * <p>Se compara contra {@link #valorJson()} en lugar de hacer {@code valueOf(valor.toUpperCase())}
	 * para exigir exactamente la forma que esta misma clase produce. Con el {@code toUpperCase}, la
	 * API aceptaba {@code "ONDAS"} pero jamás lo devolvía: dos contratos donde debería haber uno.
	 *
	 * <p>El mensaje enumera los valores válidos porque es lo único que le sirve a quien se equivocó.
	 * Jackson envuelve esta excepción en una {@code HttpMessageNotReadableException}, y el manejador
	 * global rescata este texto de la cadena de causas para que llegue al cliente.
	 */
	@JsonCreator
	public static Fondo desdeJson(String valor) {
		Fondo resultado = Stream.of(values())
			.filter((fondo) -> fondo.valorJson().equals(valor))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
					"«%s» no es un fondo válido. Los admitidos son: %s".formatted(valor, valoresAdmitidos())));
		return resultado;
	}

	/** Los valores tal y como viajan en JSON, para poder nombrarlos en los mensajes de error. */
	public static String valoresAdmitidos() {
		String resultado = Stream.of(values()).map(Fondo::valorJson).collect(Collectors.joining(", "));
		return resultado;
	}

}

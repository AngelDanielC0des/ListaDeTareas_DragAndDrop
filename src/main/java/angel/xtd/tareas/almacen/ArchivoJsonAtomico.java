package angel.xtd.tareas.almacen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import angel.xtd.tareas.error.AlmacenamientoException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Un archivo JSON que se lee entero y se escribe sin dejarlo nunca a medias.
 *
 * <p><b>Por qué existe.</b> {@link AlmacenTareas} y {@link AlmacenFondos} tenían esto copiado línea
 * por línea: el mismo sufijo temporal, el mismo {@code guardarEnArchivo}, el mismo
 * {@code moverSobrescribiendo} y el mismo respaldo para cuando el sistema de archivos no admite
 * movimientos atómicos. Que estuviera duplicado no era teórico: el fallo de dejarse el archivo
 * temporal tirado cuando la escritura fallaba estaba en las <b>dos</b> copias, precisamente porque
 * se copiaron juntas, y arreglarlo obligaba a acordarse de tocar las dos.
 *
 * <p>Aquí queda un solo sitio donde está la fontanería, y cada almacén se queda con lo suyo: qué
 * estructura de datos guarda, qué invariantes cumple y cómo se sincroniza.
 *
 * <h2>Por qué la escritura es en dos pasos</h2>
 * Se escribe en un archivo temporal y luego se mueve sobre el definitivo. Escribir directamente
 * sobre el bueno significa truncarlo primero: si el proceso muere a la mitad —o se va la luz—, lo
 * que queda en el disco es medio JSON que ya no se puede leer, y los datos anteriores se han
 * perdido. Con el movimiento, el archivo bueno solo cambia cuando el nuevo está completo.
 *
 * <p>No lleva cerrojo a propósito: cada almacén tiene el suyo y ya lo sostiene mientras llama aquí.
 * Poner otro dentro sería un segundo candado para la misma puerta.
 */
class ArchivoJsonAtomico {

	private static final Logger log = LoggerFactory.getLogger(ArchivoJsonAtomico.class);

	private static final String SUFIJO_TEMPORAL = ".tmp";

	private final ObjectMapper mapeadorJson;

	private final Path ruta;

	/** Cómo se llama el contenido en los mensajes de error: «tareas», «fondos»… */
	private final String descripcion;

	ArchivoJsonAtomico(ObjectMapper mapeadorJson, Path ruta, String descripcion) {
		this.mapeadorJson = mapeadorJson;
		this.ruta = ruta.toAbsolutePath().normalize();
		this.descripcion = descripcion;
	}

	/** La ruta absoluta y normalizada, para que los almacenes puedan nombrarla en sus propios logs. */
	Path ruta() {
		Path resultado = this.ruta;
		return resultado;
	}

	/** Si el archivo todavía no está. Un primer arranque no es un error, así que lo decide el llamante. */
	boolean existe() {
		boolean resultado = Files.exists(this.ruta);
		return resultado;
	}

	/**
	 * Lee el archivo completo.
	 *
	 * <p>Un archivo existente pero vacío devuelve {@code siEstaVacio} en lugar de reventar: es lo que
	 * queda si un guardado anterior se interrumpió justo en el peor momento, y arrancar sin datos es
	 * mejor que no arrancar.
	 *
	 * @param tipo la forma del contenido, que el genérico por sí solo no conserva en tiempo de ejecución
	 * @param siEstaVacio qué devolver si el archivo no tiene nada
	 */
	<T> T leer(TypeReference<T> tipo, T siEstaVacio) {
		try {
			String json = Files.readString(this.ruta, StandardCharsets.UTF_8);

			T resultado;
			if (json.isBlank()) {
				resultado = siEstaVacio;
			}
			else {
				resultado = this.mapeadorJson.readValue(json, tipo);
			}
			return resultado;
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException(
					"No se pudo leer el archivo de %s: %s".formatted(this.descripcion, this.ruta), excepcion);
		}
	}

	/** Escribe el contenido entero, dejando el archivo anterior intacto si algo falla por el camino. */
	void guardar(Object contenido) {
		Path temporal = this.ruta.resolveSibling(this.ruta.getFileName().toString() + SUFIJO_TEMPORAL);
		try {
			Files.createDirectories(this.ruta.getParent());
			String json = this.mapeadorJson.writerWithDefaultPrettyPrinter().writeValueAsString(contenido);
			Files.writeString(temporal, json, StandardCharsets.UTF_8);
			moverSobrescribiendo(temporal, this.ruta);
		}
		catch (IOException | JacksonException excepcion) {
			descartarTemporal(temporal);
			throw new AlmacenamientoException(
					"No se pudo guardar el archivo de %s: %s".formatted(this.descripcion, this.ruta), excepcion);
		}
	}

	/**
	 * Mueve el temporal sobre el definitivo, atómicamente si se puede.
	 *
	 * <p>El movimiento atómico no lo admiten todos los sistemas de archivos —al cruzar volúmenes, o
	 * en algunos de red—. Cuando no se puede se reemplaza igualmente, porque quedarse sin guardar
	 * sería peor; se avisa por el log de que en ese equipo la garantía es más débil.
	 */
	private void moverSobrescribiendo(Path temporal, Path destino) throws IOException {
		try {
			Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException excepcion) {
			log.warn("El sistema de archivos no admite movimiento atómico; se reemplaza sin atomicidad");
			Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Borra el archivo temporal cuando la escritura no ha llegado a completarse.
	 *
	 * <p>Va en el {@code catch} y no en un {@code finally} porque en el camino correcto el temporal ya
	 * no existe: {@code Files.move} lo ha consumido. Ponerlo en el {@code finally} añadiría una
	 * llamada al sistema de archivos a cada guardado bueno para no borrar nada.
	 *
	 * <p>Si el borrado falla se registra pero no se lanza: el error que importa es el que venía de
	 * antes, y taparlo con este dejaría sin explicar por qué no se pudo guardar.
	 */
	private void descartarTemporal(Path temporal) {
		try {
			Files.deleteIfExists(temporal);
		}
		catch (IOException excepcion) {
			log.warn("No se pudo borrar el archivo temporal {}", temporal, excepcion);
		}
	}

}

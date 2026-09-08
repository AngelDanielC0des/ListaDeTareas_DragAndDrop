package angel.xtd.tareas.almacen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.AlmacenamientoException;
import jakarta.annotation.PostConstruct;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda las tareas en memoria y las persiste en un archivo JSON.
 *
 * <p><b>No es un repository:</b> no hay ORM ni base de datos. Es simplemente el guardián del estado
 * compartido y de la E/S. Está separado del servicio a propósito: si el {@code Files.move}, el
 * {@code ObjectMapper} y el control de concurrencia vivieran dentro de {@code TareasService}, la
 * lógica de negocio quedaría enterrada bajo la fontanería.
 *
 * <h2>Estructura de datos</h2>
 * Una {@link ArrayList} en la que <b>el índice es el orden</b> de la tarea. El {@code id} es
 * identidad y no cambia nunca; el orden lo da la posición dentro del array del JSON, así que la
 * tarea solo necesita los tres campos que se guardan.
 *
 * <h2>Concurrencia</h2>
 * Todos los métodos que tocan la lista son {@code synchronized}. Es lo más simple que funciona: una
 * petición no puede leer la lista a medio modificar ni dos escrituras pueden entrelazarse.
 */
@Component
public class AlmacenTareas {

	private static final Logger log = LoggerFactory.getLogger(AlmacenTareas.class);

	private static final String SUFIJO_TEMPORAL = ".tmp";

	/** Se declara una sola vez: instanciarlo en cada carga crearía una clase anónima por llamada. */
	private static final TypeReference<List<Tarea>> TIPO_LISTA_DE_TAREAS = new TypeReference<>() {
	};

	private final ObjectMapper mapeadorJson;

	private final Path archivo;

	/** Fuente de verdad en memoria. El índice de la lista ES el orden de la tarea. */
	private final List<Tarea> tareas = new ArrayList<>();

	/** Contador monótono. Nunca decrece, así que un id no se reutiliza dentro de una misma sesión. */
	private int siguienteId = Tarea.PRIMER_ID;

	public AlmacenTareas(ObjectMapper mapeadorJson, PropiedadesAlmacen propiedades) {
		this.mapeadorJson = mapeadorJson;
		this.archivo = Path.of(propiedades.ruta()).toAbsolutePath().normalize();
	}

	/**
	 * Carga el archivo al arrancar. Si no existe, se empieza con la lista vacía: un primer arranque
	 * no es un error.
	 */
	@PostConstruct
	public synchronized void cargarDesdeArchivo() {
		try {
			if (!Files.exists(this.archivo)) {
				log.info("No existe {}; se arranca con la lista vacía", this.archivo);
				return;
			}
			String json = Files.readString(this.archivo, StandardCharsets.UTF_8);
			List<Tarea> leidas = json.isBlank() ? List.of() : this.mapeadorJson.readValue(json, TIPO_LISTA_DE_TAREAS);

			this.tareas.clear();
			this.tareas.addAll(leidas);
			this.siguienteId = calcularSiguienteId(leidas);

			log.info("Cargadas {} tareas desde {} (siguiente id = {})", this.tareas.size(), this.archivo,
					this.siguienteId);
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException("No se pudo leer el archivo de tareas: " + this.archivo, excepcion);
		}
	}

	/** Devuelve una copia inmutable, para que nadie pueda modificar el estado interno por la puerta de atrás. */
	public synchronized List<Tarea> consultarTodas() {
		List<Tarea> resultado = List.copyOf(this.tareas);
		return resultado;
	}

	public synchronized Optional<Tarea> buscarPorId(int id) {
		Optional<Tarea> resultado = this.tareas.stream().filter(tarea -> tarea.id() == id).findFirst();
		return resultado;
	}

	/**
	 * Crea una tarea al final de la lista y la guarda.
	 *
	 * <p>El id sale de un contador propio y no del tamaño de la lista. Con {@code size()} habría
	 * colisiones: con tres tareas (1, 2, 3), al borrar la 2 el tamaño baja a 2 y la siguiente tarea
	 * recibiría el id 3, machacando una existente.
	 */
	public synchronized Tarea anadirAlFinal(String texto) {
		Tarea nueva = new Tarea(this.siguienteId, texto, false);

		this.tareas.add(nueva);
		guardarDeshaciendoSiFalla(() -> this.tareas.remove(nueva));
		this.siguienteId++;

		log.debug("anadirAlFinal() -> creada tarea id={}", nueva.id());
		return nueva;
	}

	/** Sustituye una tarea por otra con el mismo id, en su misma posición. */
	public synchronized boolean reemplazar(Tarea tarea) {
		int posicion = buscarPosicion(tarea.id());
		if (posicion == -1) {
			return false;
		}

		Tarea anterior = this.tareas.set(posicion, tarea);
		guardarDeshaciendoSiFalla(() -> this.tareas.set(posicion, anterior));

		log.debug("reemplazar() -> actualizada tarea id={}", tarea.id());
		return true;
	}

	public synchronized boolean eliminar(int id) {
		int posicion = buscarPosicion(id);
		if (posicion == -1) {
			return false;
		}

		Tarea eliminada = this.tareas.remove(posicion);
		guardarDeshaciendoSiFalla(() -> this.tareas.add(posicion, eliminada));

		log.debug("eliminar() -> eliminada tarea id={}", id);
		return true;
	}

	private int buscarPosicion(int id) {
		for (int posicion = 0; posicion < this.tareas.size(); posicion++) {
			if (this.tareas.get(posicion).id() == id) {
				return posicion;
			}
		}
		return -1;
	}

	/**
	 * Guarda y, si la escritura falla, revierte el cambio que se acababa de hacer en memoria.
	 *
	 * <p>Sin esto, un fallo de disco dejaría la lista en memoria diciendo una cosa y el archivo otra,
	 * y el usuario vería su cambio aplicado aunque no se hubiera guardado.
	 */
	private void guardarDeshaciendoSiFalla(Runnable deshacer) {
		try {
			guardarEnArchivo();
		}
		catch (RuntimeException excepcion) {
			deshacer.run();
			throw excepcion;
		}
	}

	/**
	 * Escribe primero en un archivo temporal y luego lo mueve sobre el definitivo. Si el proceso
	 * muere a mitad de la escritura, el archivo bueno sigue intacto en lugar de quedar truncado.
	 */
	private void guardarEnArchivo() {
		Path temporal = this.archivo.resolveSibling(this.archivo.getFileName().toString() + SUFIJO_TEMPORAL);
		try {
			Files.createDirectories(this.archivo.getParent());
			String json = this.mapeadorJson.writerWithDefaultPrettyPrinter().writeValueAsString(this.tareas);
			Files.writeString(temporal, json, StandardCharsets.UTF_8);
			moverSobrescribiendo(temporal, this.archivo);
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException("No se pudo guardar el archivo de tareas: " + this.archivo, excepcion);
		}
	}

	private void moverSobrescribiendo(Path temporal, Path destino) throws IOException {
		try {
			Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException excepcion) {
			log.warn("El sistema de archivos no admite movimiento atómico; se reemplaza sin atomicidad");
			Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private int calcularSiguienteId(List<Tarea> leidas) {
		int mayorId = leidas.stream().mapToInt(Tarea::id).max().orElse(Tarea.PRIMER_ID - 1);
		int resultado = Math.max(mayorId + 1, Tarea.PRIMER_ID);
		return resultado;
	}

}

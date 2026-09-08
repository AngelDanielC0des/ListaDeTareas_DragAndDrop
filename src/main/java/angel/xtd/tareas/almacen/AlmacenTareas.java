package angel.xtd.tareas.almacen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntSupplier;

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
 * {@code ObjectMapper} y el cerrojo vivieran dentro de {@code TareasService}, la lógica de negocio
 * quedaría enterrada bajo la fontanería.
 *
 * <h2>Estructura de datos: {@code ArrayList}, no {@code TreeMap}</h2>
 * El orden de las tareas es la posición en la lista. Un {@code TreeMap} sirve para claves dispersas
 * con búsqueda O(log n); aquí las claves serían densas y consecutivas (0..N-1), que es exactamente
 * la definición de un array. Además la operación estrella de la app —mover una tarea— es la peor
 * para el árbol: O(n·log n) reasignando la clave de todo el rango afectado, frente a O(n) de un
 * {@code System.arraycopy}.
 *
 * <h2>Concurrencia</h2>
 * Un {@link ReentrantReadWriteLock} permite lecturas concurrentes y serializa las escrituras. Toda
 * modificación pasa por {@link #modificar(OperacionDeModificacion)}, que hace leer-modificar-guardar
 * bajo un único bloqueo: así dos peticiones simultáneas no pueden entrelazarse.
 *
 * <p><b>Limitación conocida y aceptada:</b> el cerrojo de escritura se mantiene durante la escritura
 * en disco, de modo que las lecturas se bloquean mientras se guarda. Soltarlo antes obligaría a
 * llevar un número de secuencia para garantizar que las escrituras llegan al archivo en el orden
 * correcto; con un único usuario esa complejidad no compensa.
 */
@Component
public class AlmacenTareas {

	/**
	 * Qué hacer con la lista dentro de una modificación atómica.
	 *
	 * <p>Recibe el generador de ids como parámetro en lugar de exponerlo como método público del
	 * almacén: así reservar un id <b>solo</b> es posible dentro de una modificación, que es la única
	 * situación en la que la reserva y el guardado ocurren bajo el mismo bloqueo. Antes era un método
	 * público y el contrato dependía de que quien lo llamara se acordase.
	 */
	@FunctionalInterface
	public interface OperacionDeModificacion<R> {

		R aplicar(List<Tarea> tareas, IntSupplier reservarId);

	}

	private static final Logger log = LoggerFactory.getLogger(AlmacenTareas.class);

	private static final String SUFIJO_TEMPORAL = ".tmp";

	/** Se declara una sola vez: instanciarlo en cada carga crearía una clase anónima por llamada. */
	private static final TypeReference<List<Tarea>> TIPO_LISTA_DE_TAREAS = new TypeReference<>() {
	};

	private final ObjectMapper mapeadorJson;

	private final Path archivo;

	private final ReentrantReadWriteLock cerrojo = new ReentrantReadWriteLock();

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
	 *
	 * <p>En la aplicación lo llama Spring por el {@code @PostConstruct}; es público porque las pruebas
	 * del servicio lo invocan a mano para simular un reinicio, y viven en otro paquete.
	 */
	@PostConstruct
	public void cargarDesdeArchivo() {
		this.cerrojo.writeLock().lock();
		try {
			if (!Files.exists(this.archivo)) {
				log.info("No existe {}; se arranca con la lista vacía", this.archivo);
				return;
			}
			String json = Files.readString(this.archivo, StandardCharsets.UTF_8);
			List<Tarea> leidas = json.isBlank() ? List.of() : this.mapeadorJson.readValue(json, TIPO_LISTA_DE_TAREAS);

			this.tareas.clear();
			this.tareas.addAll(leidas);
			validarIdsUnicos(leidas);
			this.siguienteId = calcularSiguienteId(leidas);

			log.info("Cargadas {} tareas desde {} (siguiente id = {})", this.tareas.size(), this.archivo,
					this.siguienteId);
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException("No se pudo leer el archivo de tareas: " + this.archivo, excepcion);
		}
		finally {
			this.cerrojo.writeLock().unlock();
		}
	}

	/** Devuelve una copia inmutable, para que nadie pueda modificar el estado interno por la puerta de atrás. */
	public List<Tarea> consultarTodas() {
		this.cerrojo.readLock().lock();
		try {
			List<Tarea> resultado = List.copyOf(this.tareas);
			return resultado;
		}
		finally {
			this.cerrojo.readLock().unlock();
		}
	}

	/**
	 * Busca una tarea concreta sin copiar la lista entera.
	 *
	 * <p>Recorrer aquí evita que quien solo quiere una tarea tenga que pedir {@link #consultarTodas()}
	 * y pagar una copia O(n) para después descartarla.
	 */
	public Optional<Tarea> buscarPorId(int id) {
		this.cerrojo.readLock().lock();
		try {
			Optional<Tarea> resultado = this.tareas.stream().filter(tarea -> tarea.id() == id).findFirst();
			return resultado;
		}
		finally {
			this.cerrojo.readLock().unlock();
		}
	}

	/**
	 * Ejecuta una modificación de la lista de forma atómica y la persiste.
	 *
	 * <p>La operación recibe la lista real y puede mutarla, además de un generador con el que reservar
	 * ids nuevos. Al terminar se escribe el archivo; si la escritura falla, se restauran la lista y el
	 * contador de ids al estado previo para que memoria y disco nunca queden desincronizados.
	 *
	 * @param operacion qué hacer con la lista; devuelve lo que el servicio quiera comunicar
	 * @return lo que devuelva la operación
	 */
	public <R> R modificar(OperacionDeModificacion<R> operacion) {
		this.cerrojo.writeLock().lock();
		try {
			List<Tarea> copiaSeguridad = List.copyOf(this.tareas);
			int idAntesDeOperar = this.siguienteId;
			try {
				R resultado = operacion.aplicar(this.tareas, this::reservarSiguienteId);
				guardarEnArchivo();
				log.debug("modificar() -> {} tareas persistidas", this.tareas.size());
				return resultado;
			}
			catch (RuntimeException excepcion) {
				this.tareas.clear();
				this.tareas.addAll(copiaSeguridad);
				this.siguienteId = idAntesDeOperar;
				throw excepcion;
			}
		}
		finally {
			this.cerrojo.writeLock().unlock();
		}
	}

	/**
	 * Reserva el siguiente id libre y avanza el contador.
	 *
	 * <p>Sustituye al {@code nuevoId = mapa.size()} del esqueleto, que colisionaba: con 3 tareas
	 * (1, 2, 3), al borrar la 2 el tamaño baja a 2 y la siguiente tarea recibiría el id 3, machacando
	 * una existente.
	 */
	private int reservarSiguienteId() {
		int resultado = this.siguienteId;
		this.siguienteId++;
		return resultado;
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
		finally {
			try { Files.deleteIfExists(temporal); } catch (IOException ignored) {}
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

	private void validarIdsUnicos(List<Tarea> tareas) {
		Set<Integer> vistos = new HashSet<>();
		for (Tarea tarea : tareas) {
			if (!vistos.add(tarea.id())) {
				throw new IllegalStateException(
					"El archivo contiene ids repetidos: " + tarea.id());
			}
		}
	}

}

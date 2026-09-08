package angel.xtd.tareas.almacen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.error.AlmacenamientoException;
import jakarta.annotation.PostConstruct;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda qué fondo tiene cada tarea.
 *
 * <p><b>Por qué vive aparte y no dentro de la tarea.</b> Una tarea son tres campos —id, texto y
 * completada— y así se queda: el fondo es decoración, no parte de lo que el usuario escribió. En vez
 * de ensuchar el modelo se guarda una asociación en su propio archivo, de forma que
 * {@code tareas.json} no cambia ni una coma.
 *
 * <p>Se usa un {@link TreeMap} y no un {@code HashMap} por una razón práctica: mantiene las claves
 * ordenadas, así que el archivo sale con los ids de menor a mayor y las diferencias entre versiones
 * se leen bien. Con esta cantidad de datos el coste O(log n) frente a O(1) es irrelevante.
 *
 * <p>Las entradas se limpian solas: al borrar una tarea se borra su fondo. Sin eso el archivo
 * acumularía asociaciones de tareas que ya no existen, y un id reutilizado tras reiniciar heredaría
 * un fondo que nadie eligió.
 */
@Component
public class AlmacenFondos {

	private static final Logger log = LoggerFactory.getLogger(AlmacenFondos.class);

	private static final String SUFIJO_TEMPORAL = ".tmp";

	private static final TypeReference<Map<Integer, Fondo>> TIPO_MAPA_DE_FONDOS = new TypeReference<>() {
	};

	private final ObjectMapper mapeadorJson;

	private final Path archivo;

	/** Id de la tarea al fondo elegido. Solo aparecen las tareas que tienen uno. */
	private final Map<Integer, Fondo> fondosPorTarea = new TreeMap<>();

	public AlmacenFondos(ObjectMapper mapeadorJson, PropiedadesAlmacen propiedades) {
		this.mapeadorJson = mapeadorJson;
		this.archivo = Path.of(propiedades.rutaDeFondos()).toAbsolutePath().normalize();
	}

	@PostConstruct
	public synchronized void cargarDesdeArchivo() {
		try {
			if (!Files.exists(this.archivo)) {
				log.info("No existe {}; ninguna tarea tiene fondo todavía", this.archivo);
				return;
			}
			String json = Files.readString(this.archivo, StandardCharsets.UTF_8);
			if (!json.isBlank()) {
				this.fondosPorTarea.putAll(this.mapeadorJson.readValue(json, TIPO_MAPA_DE_FONDOS));
			}
			log.info("Cargados {} fondos desde {}", this.fondosPorTarea.size(), this.archivo);
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException("No se pudo leer el archivo de fondos: " + this.archivo, excepcion);
		}
	}

	/** Copia inmutable, para que nadie modifique el mapa interno por la puerta de atrás. */
	public synchronized Map<Integer, Fondo> consultarTodos() {
		Map<Integer, Fondo> resultado = Map.copyOf(this.fondosPorTarea);
		return resultado;
	}

	/**
	 * Asigna un fondo a una tarea. {@link Fondo#NINGUNO} equivale a quitarlo.
	 *
	 * <p>Guardar el «ninguno» como ausencia de entrada y no como un valor es lo que evita que el
	 * archivo se llene de tareas sin fondo.
	 */
	public synchronized void asignar(int idDeTarea, Fondo fondo) {
		Fondo anterior = (fondo == Fondo.NINGUNO) ? this.fondosPorTarea.remove(idDeTarea)
				: this.fondosPorTarea.put(idDeTarea, fondo);

		guardarDeshaciendoSiFalla(() -> restaurar(idDeTarea, anterior));
		log.debug("asignar({}, {})", idDeTarea, fondo);
	}

	/** Se llama al borrar una tarea, para que no queden fondos de tareas inexistentes. */
	public synchronized void olvidar(int idDeTarea) {
		if (!this.fondosPorTarea.containsKey(idDeTarea)) {
			return;
		}
		Fondo anterior = this.fondosPorTarea.remove(idDeTarea);
		guardarDeshaciendoSiFalla(() -> restaurar(idDeTarea, anterior));
		log.debug("olvidar({})", idDeTarea);
	}

	/** Descarta los fondos de tareas que ya no existen. Se usa al arrancar y tras reordenar. */
	public synchronized void conservarSolo(Collection<Integer> idsQueExisten) {
		boolean cambio = this.fondosPorTarea.keySet().retainAll(idsQueExisten);
		if (cambio) {
			guardarEnArchivo();
			log.info("Limpiados los fondos de tareas que ya no existen");
		}
	}

	private void restaurar(int idDeTarea, Fondo anterior) {
		if (anterior == null) {
			this.fondosPorTarea.remove(idDeTarea);
		}
		else {
			this.fondosPorTarea.put(idDeTarea, anterior);
		}
	}

	private void guardarDeshaciendoSiFalla(Runnable deshacer) {
		try {
			guardarEnArchivo();
		}
		catch (RuntimeException excepcion) {
			deshacer.run();
			throw excepcion;
		}
	}

	/** Misma escritura atómica que el almacén de tareas: temporal y movimiento sobre el definitivo. */
	private void guardarEnArchivo() {
		Path temporal = this.archivo.resolveSibling(this.archivo.getFileName().toString() + SUFIJO_TEMPORAL);
		try {
			Files.createDirectories(this.archivo.getParent());
			String json = this.mapeadorJson.writerWithDefaultPrettyPrinter().writeValueAsString(this.fondosPorTarea);
			Files.writeString(temporal, json, StandardCharsets.UTF_8);
			moverSobrescribiendo(temporal, this.archivo);
		}
		catch (IOException | JacksonException excepcion) {
			throw new AlmacenamientoException("No se pudo guardar el archivo de fondos: " + this.archivo, excepcion);
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

}

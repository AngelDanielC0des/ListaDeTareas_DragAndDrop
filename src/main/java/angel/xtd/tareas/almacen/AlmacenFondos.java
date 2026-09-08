package angel.xtd.tareas.almacen;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Fondo;
import jakarta.annotation.PostConstruct;
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

	private static final TypeReference<Map<Integer, Fondo>> TIPO_MAPA_DE_FONDOS = new TypeReference<>() {
	};

	private final ArchivoJsonAtomico archivo;

	/** Id de la tarea al fondo elegido. Solo aparecen las tareas que tienen uno. */
	private final Map<Integer, Fondo> fondosPorTarea = new TreeMap<>();

	public AlmacenFondos(ObjectMapper mapeadorJson, PropiedadesAlmacen propiedades) {
		this.archivo = new ArchivoJsonAtomico(mapeadorJson, Path.of(propiedades.rutaDeFondos()), "fondos");
	}

	@PostConstruct
	public synchronized void cargarDesdeArchivo() {
		if (!this.archivo.existe()) {
			log.info("No existe {}; ninguna tarea tiene fondo todavía", this.archivo.ruta());
		}
		else {
			Map<Integer, Fondo> leidos = this.archivo.leer(TIPO_MAPA_DE_FONDOS, Map.of());

			// El clear va fuera del «si el archivo trae algo»: recargar sobre un archivo que se ha
			// quedado vacío tiene que dejar el mapa vacío, no conservar lo de la carga anterior. Es
			// el mismo orden que sigue AlmacenTareas.
			this.fondosPorTarea.clear();
			this.fondosPorTarea.putAll(leidos);
			log.info("Cargados {} fondos desde {}", this.fondosPorTarea.size(), this.archivo.ruta());
		}
	}

	/**
	 * Copia inmutable, para que nadie modifique el mapa interno por la puerta de atrás.
	 *
	 * <p>Se copia a otro {@link TreeMap} en vez de usar {@code Map.copyOf}: ese devuelve un mapa sin
	 * orden garantizado, con lo que la ordenación por id —que es justo para lo que se eligió un
	 * árbol— se perdía en el único punto por el que los fondos salen de la clase.
	 */
	public synchronized Map<Integer, Fondo> consultarTodos() {
		Map<Integer, Fondo> resultado = Collections.unmodifiableMap(new TreeMap<>(this.fondosPorTarea));
		return resultado;
	}

	/**
	 * Asigna un fondo a una tarea. {@link Fondo#NINGUNO} equivale a quitarlo.
	 *
	 * <p>Guardar el «ninguno» como ausencia de entrada y no como un valor es lo que evita que el
	 * archivo se llene de tareas sin fondo.
	 */
	public synchronized void asignar(int idDeTarea, Fondo fondo) {
		Fondo anterior = this.fondosPorTarea.get(idDeTarea);
		Fondo deseado;
		if (fondo == Fondo.NINGUNO) {
			// «Ninguno» se guarda como ausencia de entrada, no como un valor: así el archivo no se
			// llena de tareas que no tienen fondo.
			deseado = null;
		}
		else {
			deseado = fondo;
		}

		// Se comprueba ANTES de tocar el mapa. Hacerlo al revés —mutar y después decidir que no
		// hacía falta— funciona, pero obliga a razonar hacia atrás para convencerse de que el mapa
		// queda bien, y basta con reordenar dos líneas para no tener que hacerlo.
		if (deseado == anterior) {
			log.debug("asignar({}, {}) -> ya estaba así, no se reescribe el archivo", idDeTarea, fondo);
		}
		else {
			if (deseado == null) {
				this.fondosPorTarea.remove(idDeTarea);
			}
			else {
				this.fondosPorTarea.put(idDeTarea, deseado);
			}
			guardarDeshaciendoSiFalla(() -> restaurar(idDeTarea, anterior));
			log.debug("asignar({}, {})", idDeTarea, fondo);
		}
	}

	/** Se llama al borrar una tarea, para que no queden fondos de tareas inexistentes. */
	public synchronized void olvidar(int idDeTarea) {
		Fondo anterior = this.fondosPorTarea.remove(idDeTarea);
		if (anterior == null) {
			log.debug("olvidar({}) -> no tenía fondo, no se reescribe el archivo", idDeTarea);
		}
		else {
			guardarDeshaciendoSiFalla(() -> restaurar(idDeTarea, anterior));
			log.debug("olvidar({})", idDeTarea);
		}
	}

	/**
	 * Descarta los fondos de tareas que ya no existen. Se usa <b>solo al arrancar</b>.
	 *
	 * <p>No hace falta llamarla al reordenar: reordenar no cambia ningún id —esa es una decisión de
	 * diseño central del proyecto— así que ahí no puede aparecer ningún fondo huérfano.
	 *
	 * <p>Los ids se pasan a un {@link HashSet} antes del {@code retainAll} porque ese método consulta
	 * la colección una vez por clave: con una {@code List} cada consulta es lineal y el conjunto sale
	 * cuadrático.
	 */
	public synchronized void conservarSolo(Collection<Integer> idsQueExisten) {
		Set<Integer> idsParaBuscarRapido = new HashSet<>(idsQueExisten);
		boolean cambio = this.fondosPorTarea.keySet().retainAll(idsParaBuscarRapido);
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

	private void guardarEnArchivo() {
		this.archivo.guardar(this.fondosPorTarea);
		log.debug("Guardados {} fondos en {}", this.fondosPorTarea.size(), this.archivo.ruta());
	}

}

package angel.xtd.tareas.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import angel.xtd.tareas.almacen.AlmacenFondos;
import angel.xtd.tareas.almacen.AlmacenGrupos;
import angel.xtd.tareas.almacen.AlmacenTareas;
import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.dto.Grupo;
import angel.xtd.tareas.dto.GruposConAsignaciones;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.OrdenInvalidoException;
import angel.xtd.tareas.error.TareaNoEncontradaException;
import jakarta.annotation.PostConstruct;

/**
 * Lógica de negocio de la lista de tareas.
 *
 * <p>No sabe nada de HTTP ni de archivos: recibe y devuelve objetos de dominio, y lanza excepciones
 * de dominio. Traducir eso a códigos de estado es trabajo del manejador global de errores; leer y
 * escribir el JSON, del almacén.
 *
 * <p>Toda mutación se ejecuta dentro de {@link AlmacenTareas#modificar}, para que leer-modificar-
 * guardar ocurra bajo un único bloqueo y no pueda entrelazarse con otra petición.
 */
@Service
public class TareasService {

	private static final Logger log = LoggerFactory.getLogger(TareasService.class);

	/** Valor con el que arranca la búsqueda de posición, para distinguir «todavía no encontrada». */
	private static final int POSICION_NO_ENCONTRADA = -1;

	private final AlmacenTareas almacen;

	private final AlmacenFondos fondos;

	private final AlmacenGrupos grupos;

	public TareasService(AlmacenTareas almacen, AlmacenFondos fondos, AlmacenGrupos grupos) {
		this.almacen = almacen;
		this.fondos = fondos;
		this.grupos = grupos;
	}

	/**
	 * Al arrancar, descarta los fondos y los grupos de tareas que ya no existen.
	 *
	 * <p>Durante el uso normal basta con olvidar la asociación al borrar la tarea, pero los archivos
	 * se escriben por separado: si el proceso muere justo entre dos escrituras, queda un fondo o un
	 * grupo huérfano. Y como el contador de ids se recalcula como {@code max(id) + 1} al cargar, ese
	 * id puede volver a repartirse y la tarea nueva heredaría cosas que nadie eligió para ella.
	 *
	 * <p>Spring garantiza que los tres almacenes están construidos —y por tanto cargados— antes de
	 * inyectarlos aquí, así que este es el primer momento en que se pueden comparar.
	 */
	@PostConstruct
	void descartarAsociacionesHuerfanas() {
		List<Integer> idsQueExisten = this.almacen.consultarTodas().stream().map(Tarea::id).toList();
		this.fondos.conservarSolo(idsQueExisten);
		this.grupos.conservarSoloTareas(idsQueExisten);
	}

	public List<Tarea> consultarTodas() {
		List<Tarea> resultado = this.almacen.consultarTodas();
		log.debug("consultarTodas() -> {} tareas", resultado.size());
		return resultado;
	}

	public Tarea consultarPorId(int id) {
		Tarea resultado = this.almacen.buscarPorId(id).orElseThrow(() -> new TareaNoEncontradaException(id));
		log.debug("consultarPorId({}) -> encontrada", id);
		return resultado;
	}

	/** La tarea nueva se añade al final de la lista, que es donde el usuario espera verla aparecer. */
	public Tarea crear(String texto) {
		String textoLimpio = normalizarTexto(texto);
		Tarea resultado = this.almacen.modificar((tareas, reservarId) -> {
			Tarea nueva = new Tarea(reservarId.getAsInt(), textoLimpio, false);
			tareas.add(nueva);
			return nueva;
		});
		log.info("crear() -> creada tarea id={}", resultado.id());
		return resultado;
	}

	/** Reemplaza texto y estado conservando el id y, sobre todo, la posición en la lista. */
	public Tarea actualizar(int id, String texto, boolean completada) {
		String textoLimpio = normalizarTexto(texto);
		Tarea resultado = reemplazarEnPosicion(id, anterior -> new Tarea(id, textoLimpio, completada));
		log.info("actualizar({}) -> actualizada", id);
		return resultado;
	}

	public Tarea cambiarCompletada(int id, boolean completada) {
		Tarea resultado = reemplazarEnPosicion(id, anterior -> anterior.conCompletada(completada));
		log.info("cambiarCompletada({}, {}) -> actualizada", id, completada);
		return resultado;
	}

	public void eliminar(int id) {
		this.almacen.modificar((tareas, reservarId) -> {
			int posicion = buscarPosicionDeTarea(tareas, id);
			return tareas.remove(posicion);
		});

		// Si no se olvidaran, los archivos acumularían tareas que ya no existen y un id reutilizado
		// tras reiniciar heredaría un fondo o un grupo que nadie eligió para él.
		this.fondos.olvidar(id);
		this.grupos.olvidarTarea(id);

		log.info("eliminar({}) -> eliminada", id);
	}

	/* ------------------------------------------------------------------ Grupos */

	public GruposConAsignaciones consultarGrupos() {
		GruposConAsignaciones resultado = this.grupos.consultarTodo();
		log.debug("consultarGrupos() -> {} grupos", resultado.grupos().size());
		return resultado;
	}

	public Grupo crearGrupo(String nombre) {
		Grupo resultado = this.grupos.crear(normalizarTexto(nombre));
		log.info("crearGrupo() -> creado grupo id={}", resultado.id());
		return resultado;
	}

	public Grupo renombrarGrupo(int idDeGrupo, String nombre) {
		Grupo resultado = this.grupos.renombrar(idDeGrupo, normalizarTexto(nombre));
		log.info("renombrarGrupo({})", idDeGrupo);
		return resultado;
	}

	/** Borra el grupo. Sus tareas no se borran: se quedan sueltas. */
	public void eliminarGrupo(int idDeGrupo) {
		this.grupos.eliminar(idDeGrupo);
		log.info("eliminarGrupo({}) -> eliminado", idDeGrupo);
	}

	public List<Grupo> reordenarGrupos(List<Integer> idsEnOrden) {
		List<Grupo> resultado = this.grupos.reordenar(idsEnOrden);
		log.info("reordenarGrupos() -> nuevo orden {}", idsEnOrden);
		return resultado;
	}

	/**
	 * Mete una tarea en un grupo, o la deja suelta si {@code idDeGrupo} es {@code null}.
	 *
	 * <p>Se comprueba antes que la tarea exista, por lo mismo que con el fondo: si no, se guardaría
	 * la pertenencia de algo que no está y quedaría ahí hasta el siguiente arranque.
	 */
	public GruposConAsignaciones cambiarGrupo(int id, Integer idDeGrupo) {
		if (this.almacen.buscarPorId(id).isEmpty()) {
			throw new TareaNoEncontradaException(id);
		}
		this.grupos.asignar(id, idDeGrupo);

		GruposConAsignaciones resultado = this.grupos.consultarTodo();
		log.info("cambiarGrupo({}, {})", id, idDeGrupo);
		return resultado;
	}

	/* -------------------------------------------------------- Fondo de las tarjetas */

	public Map<Integer, Fondo> consultarFondos() {
		Map<Integer, Fondo> resultado = this.fondos.consultarTodos();
		log.debug("consultarFondos() -> {} tareas con fondo", resultado.size());
		return resultado;
	}

	/**
	 * Asigna el fondo de una tarea.
	 *
	 * <p>Se comprueba antes que la tarea exista: si no, se guardaría el fondo de algo que no está y
	 * quedaría ahí para siempre, porque la limpieza solo se dispara al borrar.
	 */
	public void cambiarFondo(int id, Fondo fondo) {
		if (this.almacen.buscarPorId(id).isEmpty()) {
			throw new TareaNoEncontradaException(id);
		}
		this.fondos.asignar(id, fondo);
		log.info("cambiarFondo({}, {})", id, fondo);
	}

	/**
	 * Aplica el orden del drag &amp; drop.
	 *
	 * <p>Recibe todos los ids en el orden deseado y reconstruye la lista. <b>Ningún id cambia:</b>
	 * lo que se mueve es la posición. Renumerar los ids al arrastrar haría que una petición en vuelo
	 * (por ejemplo un {@code DELETE} lanzado justo antes) acabase afectando a otra tarea.
	 */
	public List<Tarea> reordenar(List<Integer> idsEnOrden) {
		List<Tarea> resultado = this.almacen.modificar((tareas, reservarId) -> {
			validarQueEsPermutacionExacta(tareas, idsEnOrden);

			Map<Integer, Tarea> tareasPorId = tareas.stream()
				.collect(Collectors.toMap(Tarea::id, Function.identity()));
			List<Tarea> reordenadas = idsEnOrden.stream().map(tareasPorId::get).toList();

			tareas.clear();
			tareas.addAll(reordenadas);
			return List.copyOf(tareas);
		});
		// Aquí NO se llama a conservarSolo: validarQueEsPermutacionExacta ya garantiza que los ids
		// recibidos son exactamente los que hay, así que no puede sobrar ninguno. Sería recorrer la
		// lista entera para no borrar nunca nada.
		log.info("reordenar() -> nuevo orden de ids {}", idsEnOrden);
		return resultado;
	}

	/**
	 * Sustituye una tarea por otra derivada de ella, en su misma posición.
	 *
	 * <p>Recoge el patrón que compartían {@link #actualizar} y {@link #cambiarCompletada}: localizar
	 * la posición, construir la tarea nueva y colocarla donde estaba la anterior. Lo único que
	 * cambiaba entre ambas era cómo se construye la tarea nueva, y eso es lo que recibe como
	 * parámetro.
	 */
	private Tarea reemplazarEnPosicion(int id, UnaryOperator<Tarea> transformacion) {
		Tarea resultado = this.almacen.modificar((tareas, reservarId) -> {
			int posicion = buscarPosicionDeTarea(tareas, id);
			Tarea actualizada = transformacion.apply(tareas.get(posicion));
			tareas.set(posicion, actualizada);
			return actualizada;
		});
		return resultado;
	}

	/**
	 * Comprueba que lo recibido es una permutación EXACTA de las tareas actuales: ni repetidos, ni
	 * ids desconocidos, ni tareas que se quedan fuera. Sin esto, un cliente con la lista
	 * desactualizada podría borrar tareas sin querer al reordenar.
	 */
	private void validarQueEsPermutacionExacta(List<Tarea> tareas, List<Integer> idsEnOrden) {
		Set<Integer> idsRecibidos = new LinkedHashSet<>(idsEnOrden);
		if (idsRecibidos.size() != idsEnOrden.size()) {
			throw new OrdenInvalidoException("El nuevo orden contiene ids repetidos");
		}

		Set<Integer> idsActuales = tareas.stream().map(Tarea::id).collect(Collectors.toCollection(LinkedHashSet::new));
		if (!idsRecibidos.equals(idsActuales)) {
			Set<Integer> desconocidos = new LinkedHashSet<>(idsRecibidos);
			desconocidos.removeAll(idsActuales);

			Set<Integer> ausentes = new LinkedHashSet<>(idsActuales);
			ausentes.removeAll(idsRecibidos);

			throw new OrdenInvalidoException(
					"El nuevo orden debe contener exactamente las %d tareas existentes. Ids desconocidos: %s; ids ausentes: %s"
						.formatted(idsActuales.size(), desconocidos, ausentes));
		}
	}

	/**
	 * Devuelve la posición de una tarea, o lanza {@link TareaNoEncontradaException} si no existe.
	 *
	 * <p>Búsqueda lineal: con listas de tareas es más rápida que cualquier índice, y no hay que
	 * mantenerla al reordenar.
	 *
	 * <p>El bucle no sale por el medio con un {@code return}: acumula el resultado en una variable y
	 * corta por la condición, que es la convención que sigue el resto del proyecto —«el valor de
	 * retorno pasa por una variable»— y deja un único punto de salida que es fácil de instrumentar.
	 */
	private int buscarPosicionDeTarea(List<Tarea> tareas, int id) {
		int resultado = POSICION_NO_ENCONTRADA;
		for (int posicion = 0; posicion < tareas.size() && resultado == POSICION_NO_ENCONTRADA; posicion++) {
			if (tareas.get(posicion).id() == id) {
				resultado = posicion;
			}
		}

		if (resultado == POSICION_NO_ENCONTRADA) {
			throw new TareaNoEncontradaException(id);
		}
		return resultado;
	}

	/** Quita espacios sobrantes de los extremos; {@code @NotBlank} ya ha descartado el texto vacío. */
	private String normalizarTexto(String texto) {
		String resultado;
		if (texto == null) {
			resultado = "";
		}
		else {
			resultado = texto.strip();
		}
		return resultado;
	}

}

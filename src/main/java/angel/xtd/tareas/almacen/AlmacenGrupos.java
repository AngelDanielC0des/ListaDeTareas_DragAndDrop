package angel.xtd.tareas.almacen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Grupo;
import angel.xtd.tareas.dto.GruposConAsignaciones;
import angel.xtd.tareas.error.AlmacenamientoException;
import angel.xtd.tareas.error.GrupoNoEncontradoException;
import angel.xtd.tareas.error.OrdenInvalidoException;
import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda los grupos y a qué grupo pertenece cada tarea.
 *
 * <p><b>Por qué vive aparte y no dentro de la tarea.</b> Una tarea son tres campos —id, texto y
 * completada— y así se queda. El grupo es una relación entre tareas, no un atributo de una tarea, de
 * modo que en vez de ensanchar el modelo se guarda la asociación en su propio archivo. Es
 * exactamente el mismo razonamiento que llevó a {@link AlmacenFondos} a existir.
 *
 * <h2>Por qué los grupos y las asignaciones van en el mismo archivo</h2>
 * Por separado no sirven de nada: los grupos sin saber quién pertenece a cuál no permiten pintar, y
 * las asignaciones sin los nombres tampoco. Y sobre todo, dos archivos son dos escrituras que pueden
 * fallar por separado: si el proceso muriera entre una y otra quedarían asignaciones apuntando a un
 * grupo que no existe. Con un solo archivo el problema no puede darse.
 *
 * <h2>El orden</h2>
 * Igual que con las tareas, <b>el orden de los grupos es su posición en la lista</b>: ningún id
 * cambia al reordenar. Y dentro de un grupo, el orden de sus tareas es el orden relativo que ya
 * tienen en la lista global de {@link AlmacenTareas}; aquí no se guarda ninguna ordenación por
 * grupo, que sería una segunda fuente de verdad para lo mismo.
 */
@Component
public class AlmacenGrupos {

	/**
	 * Lo que se persiste, tal cual.
	 *
	 * <p>Es un record propio y no dos campos sueltos porque el archivo tiene forma de objeto con dos
	 * claves, y darle un tipo evita tener que describirla con un {@code TypeReference} ilegible.
	 *
	 * @param grupos en su orden
	 * @param asignaciones id de tarea al id de su grupo
	 */
	private record Contenido(List<Grupo> grupos, Map<Integer, Integer> asignaciones) {
	}

	private static final Logger log = LoggerFactory.getLogger(AlmacenGrupos.class);

	private static final TypeReference<Contenido> TIPO_CONTENIDO = new TypeReference<>() {
	};

	/** Valor con el que arranca la búsqueda de posición, para distinguir «todavía no encontrado». */
	private static final int POSICION_NO_ENCONTRADA = -1;

	private final ArchivoJsonAtomico archivo;

	/** Fuente de verdad de los grupos. El índice de la lista ES el orden del grupo. */
	private final List<Grupo> grupos = new ArrayList<>();

	/**
	 * Id de tarea al id de su grupo. Solo aparecen las tareas que están en alguno.
	 *
	 * <p>Se usa un {@link TreeMap} por lo mismo que en los fondos: mantiene las claves ordenadas, así
	 * que el archivo sale con los ids de menor a mayor y las diferencias entre versiones se leen.
	 */
	private final Map<Integer, Integer> grupoPorTarea = new TreeMap<>();

	/** Contador monótono, para que borrar un grupo no libere su id y lo herede el siguiente. */
	private int siguienteId = Grupo.PRIMER_ID;

	public AlmacenGrupos(ObjectMapper mapeadorJson, PropiedadesAlmacen propiedades) {
		this.archivo = new ArchivoJsonAtomico(mapeadorJson, Path.of(propiedades.rutaDeGrupos()), "grupos");
	}

	@PostConstruct
	public synchronized void cargarDesdeArchivo() {
		if (!this.archivo.existe()) {
			log.info("No existe {}; todavía no hay ningún grupo", this.archivo.ruta());
		}
		else {
			Contenido leido = this.archivo.leer(TIPO_CONTENIDO, new Contenido(List.of(), Map.of()));
			List<Grupo> gruposLeidos = (leido.grupos() == null) ? List.of() : leido.grupos();
			Map<Integer, Integer> asignacionesLeidas = (leido.asignaciones() == null) ? Map.of()
					: leido.asignaciones();

			// Se valida ANTES de tocar nada, por lo mismo que en AlmacenTareas: si el archivo trae
			// ids repetidos y se hubiera cargado primero, el almacén se quedaría con datos que el
			// resto del código da por imposibles.
			validarIdsUnicos(gruposLeidos);

			this.grupos.clear();
			this.grupos.addAll(gruposLeidos);
			this.grupoPorTarea.clear();
			this.grupoPorTarea.putAll(asignacionesLeidas);
			this.siguienteId = calcularSiguienteId(gruposLeidos);

			// Una asignación a un grupo que no existe no se puede pintar y no hay forma de arreglarla
			// desde la interfaz, así que se descarta al cargar en vez de dejarla envenenando el mapa.
			descartarAsignacionesSinGrupo();

			log.info("Cargados {} grupos y {} asignaciones desde {} (siguiente id = {})", this.grupos.size(),
					this.grupoPorTarea.size(), this.archivo.ruta(), this.siguienteId);
		}
	}

	/* --------------------------------------------------------------- Consultas */

	/** Copia inmutable de todo, para que nadie modifique el estado interno por la puerta de atrás. */
	public synchronized GruposConAsignaciones consultarTodo() {
		GruposConAsignaciones resultado = new GruposConAsignaciones(List.copyOf(this.grupos),
				Collections.unmodifiableMap(new TreeMap<>(this.grupoPorTarea)));
		return resultado;
	}

	public synchronized boolean existe(int idDeGrupo) {
		boolean resultado = buscarPosicion(idDeGrupo) != POSICION_NO_ENCONTRADA;
		return resultado;
	}

	/* ------------------------------------------------------ Altas y modificaciones */

	public synchronized Grupo crear(String nombre) {
		Grupo resultado = new Grupo(this.siguienteId, nombre);

		this.grupos.add(resultado);
		this.siguienteId++;
		guardarDeshaciendoSiFalla();

		log.info("crear() -> creado grupo id={}", resultado.id());
		return resultado;
	}

	/** Cambia el nombre conservando el id y, sobre todo, la posición. */
	public synchronized Grupo renombrar(int idDeGrupo, String nombre) {
		int posicion = exigirPosicion(idDeGrupo);
		Grupo resultado = this.grupos.get(posicion).conNombre(nombre);

		this.grupos.set(posicion, resultado);
		guardarDeshaciendoSiFalla();

		log.info("renombrar({}) -> «{}»", idDeGrupo, nombre);
		return resultado;
	}

	/**
	 * Borra un grupo. <b>Sus tareas no se borran:</b> se quedan sueltas.
	 *
	 * <p>Es la decisión menos destructiva y la que espera cualquiera: un grupo es una forma de
	 * ordenar lo que hay, no un contenedor del que las tareas dependan para existir.
	 */
	public synchronized void eliminar(int idDeGrupo) {
		int posicion = exigirPosicion(idDeGrupo);

		this.grupos.remove(posicion);
		this.grupoPorTarea.values().removeIf((idGrupo) -> idGrupo == idDeGrupo);
		guardarDeshaciendoSiFalla();

		log.info("eliminar({}) -> borrado; sus tareas quedan sueltas", idDeGrupo);
	}

	/**
	 * Mete una tarea en un grupo, o la deja suelta si {@code idDeGrupo} es {@code null}.
	 *
	 * <p>Se comprueba antes de tocar el mapa: hacerlo al revés funciona, pero obliga a razonar hacia
	 * atrás para convencerse de que el estado queda bien.
	 */
	public synchronized void asignar(int idDeTarea, Integer idDeGrupo) {
		Integer anterior = this.grupoPorTarea.get(idDeTarea);

		if (Objects.equals(anterior, idDeGrupo)) {
			log.debug("asignar({}, {}) -> ya estaba así, no se reescribe el archivo", idDeTarea, idDeGrupo);
		}
		else {
			if (idDeGrupo == null) {
				this.grupoPorTarea.remove(idDeTarea);
			}
			else {
				exigirPosicion(idDeGrupo);
				this.grupoPorTarea.put(idDeTarea, idDeGrupo);
			}
			guardarDeshaciendoSiFalla();
			log.info("asignar({}, {})", idDeTarea, idDeGrupo);
		}
	}

	/**
	 * Aplica un orden nuevo a los grupos.
	 *
	 * <p>Como al reordenar tareas, se exige una permutación exacta: ni repetidos, ni desconocidos, ni
	 * grupos que se queden fuera. Sin eso, un cliente con la lista desactualizada podría hacer
	 * desaparecer grupos sin querer.
	 */
	public synchronized List<Grupo> reordenar(List<Integer> idsEnOrden) {
		validarQueEsPermutacionExacta(idsEnOrden);

		Map<Integer, Grupo> porId = new TreeMap<>();
		for (Grupo grupo : this.grupos) {
			porId.put(grupo.id(), grupo);
		}
		List<Grupo> reordenados = idsEnOrden.stream().map(porId::get).toList();

		this.grupos.clear();
		this.grupos.addAll(reordenados);
		guardarDeshaciendoSiFalla();

		log.info("reordenar() -> nuevo orden de grupos {}", idsEnOrden);
		List<Grupo> resultado = List.copyOf(this.grupos);
		return resultado;
	}

	/* ---------------------------------------------------- Limpieza de huérfanos */

	/** Se llama al borrar una tarea, para que no queden asignaciones de tareas inexistentes. */
	public synchronized void olvidarTarea(int idDeTarea) {
		Integer anterior = this.grupoPorTarea.remove(idDeTarea);
		if (anterior == null) {
			log.debug("olvidarTarea({}) -> no estaba en ningún grupo", idDeTarea);
		}
		else {
			guardarDeshaciendoSiFalla();
			log.debug("olvidarTarea({})", idDeTarea);
		}
	}

	/**
	 * Descarta las asignaciones de tareas que ya no existen. Se usa <b>solo al arrancar</b>.
	 *
	 * <p>Durante el uso normal basta con olvidar la asignación al borrar la tarea, pero los archivos
	 * se escriben por separado: si el proceso muere justo entre las dos escrituras queda una
	 * asignación huérfana, y como el contador de ids de tarea se recalcula al cargar, ese id puede
	 * volver a repartirse y la tarea nueva heredaría un grupo que nadie eligió para ella.
	 *
	 * <p>Los ids se pasan a un {@link HashSet} porque {@code removeIf} los consulta una vez por
	 * entrada: con una {@code List} cada consulta sería lineal y el conjunto saldría cuadrático.
	 */
	public synchronized void conservarSoloTareas(Collection<Integer> idsQueExisten) {
		Set<Integer> idsParaBuscarRapido = new HashSet<>(idsQueExisten);
		boolean cambio = this.grupoPorTarea.keySet().removeIf((id) -> !idsParaBuscarRapido.contains(id));

		if (cambio) {
			guardarEnArchivo();
			log.info("Limpiadas las asignaciones de tareas que ya no existen");
		}
	}

	/* ------------------------------------------------------------------ Interno */

	private int buscarPosicion(int idDeGrupo) {
		int resultado = POSICION_NO_ENCONTRADA;
		for (int posicion = 0; posicion < this.grupos.size() && resultado == POSICION_NO_ENCONTRADA; posicion++) {
			if (this.grupos.get(posicion).id() == idDeGrupo) {
				resultado = posicion;
			}
		}
		return resultado;
	}

	private int exigirPosicion(int idDeGrupo) {
		int resultado = buscarPosicion(idDeGrupo);
		if (resultado == POSICION_NO_ENCONTRADA) {
			throw new GrupoNoEncontradoException(idDeGrupo);
		}
		return resultado;
	}

	private void validarQueEsPermutacionExacta(List<Integer> idsEnOrden) {
		Set<Integer> idsRecibidos = new LinkedHashSet<>(idsEnOrden);
		if (idsRecibidos.size() != idsEnOrden.size()) {
			throw new OrdenInvalidoException("El nuevo orden de grupos contiene ids repetidos");
		}

		Set<Integer> idsActuales = new LinkedHashSet<>();
		for (Grupo grupo : this.grupos) {
			idsActuales.add(grupo.id());
		}

		if (!idsRecibidos.equals(idsActuales)) {
			Set<Integer> desconocidos = new LinkedHashSet<>(idsRecibidos);
			desconocidos.removeAll(idsActuales);

			Set<Integer> ausentes = new LinkedHashSet<>(idsActuales);
			ausentes.removeAll(idsRecibidos);

			throw new OrdenInvalidoException(
					"El nuevo orden debe contener exactamente los %d grupos existentes. Ids desconocidos: %s; ids ausentes: %s"
						.formatted(idsActuales.size(), desconocidos, ausentes));
		}
	}

	private void validarIdsUnicos(List<Grupo> leidos) {
		Set<Integer> idsVistos = new HashSet<>();
		for (Grupo grupo : leidos) {
			boolean esNuevo = idsVistos.add(grupo.id());
			if (!esNuevo) {
				throw new AlmacenamientoException(
						"El archivo %s contiene el grupo %d más de una vez".formatted(this.archivo.ruta(), grupo.id()));
			}
		}
	}

	private void descartarAsignacionesSinGrupo() {
		Set<Integer> idsDeGrupo = new HashSet<>();
		for (Grupo grupo : this.grupos) {
			idsDeGrupo.add(grupo.id());
		}

		boolean cambio = this.grupoPorTarea.values().removeIf((idGrupo) -> !idsDeGrupo.contains(idGrupo));
		if (cambio) {
			log.warn("Descartadas asignaciones que apuntaban a grupos inexistentes");
		}
	}

	private int calcularSiguienteId(List<Grupo> leidos) {
		int mayorId = leidos.stream().mapToInt(Grupo::id).max().orElse(Grupo.PRIMER_ID - 1);
		int resultado = Math.max(mayorId + 1, Grupo.PRIMER_ID);
		return resultado;
	}

	/**
	 * Persiste, y si la escritura falla deja la memoria exactamente como estaba.
	 *
	 * <p>El respaldo se toma en memoria y no releyendo el archivo, que sería más corto pero está mal:
	 * en el primer guardado el archivo todavía no existe, así que releerlo no restauraría nada y el
	 * cambio se quedaría aplicado pese al error. Es el mismo criterio que sigue
	 * {@code AlmacenTareas.modificar}: memoria y disco no pueden quedar desincronizados.
	 */
	private void guardarDeshaciendoSiFalla() {
		List<Grupo> gruposAntes = List.copyOf(this.grupos);
		Map<Integer, Integer> asignacionesAntes = Map.copyOf(this.grupoPorTarea);
		int idAntes = this.siguienteId;

		try {
			guardarEnArchivo();
		}
		catch (RuntimeException excepcion) {
			this.grupos.clear();
			this.grupos.addAll(gruposAntes);
			this.grupoPorTarea.clear();
			this.grupoPorTarea.putAll(asignacionesAntes);
			this.siguienteId = idAntes;
			throw excepcion;
		}
	}

	private void guardarEnArchivo() {
		this.archivo.guardar(new Contenido(List.copyOf(this.grupos), new TreeMap<>(this.grupoPorTarea)));
		log.debug("Guardados {} grupos en {}", this.grupos.size(), this.archivo.ruta());
	}

}

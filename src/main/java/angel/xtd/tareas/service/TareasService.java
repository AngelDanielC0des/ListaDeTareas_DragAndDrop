package angel.xtd.tareas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import angel.xtd.tareas.dto.Tarea;

/**
 * Lógica de negocio de la lista de tareas, que además guarda la lista en memoria.
 *
 * <p><b>No hay persistencia:</b> las tareas viven en un campo de esta clase. Al arrancar la
 * aplicación la lista está vacía y se va llenando según se usa; al parar el servidor se pierde todo.
 * Es intencionado en esta versión, y es la única diferencia de fondo con la rama
 * {@code listatareasv1}, que guarda lo mismo en un archivo JSON.
 *
 * <p>El orden de las tareas es la posición dentro de la lista y el {@code id} es identidad pura, así
 * que la tarea solo necesita los tres campos que se envían por JSON.
 *
 * <h2>Por qué devuelve Optional y boolean</h2>
 * En lugar de lanzar una excepción cuando la tarea no existe, se informa con el valor de retorno.
 * Así el controlador puede responder un 404 él mismo y esta versión no necesita ni excepciones
 * propias ni un manejador global de errores.
 *
 * <h2>Concurrencia</h2>
 * Los métodos son {@code synchronized} porque el servidor atiende varias peticiones a la vez y todas
 * comparten esta misma lista. Sin ello, dos altas simultáneas podrían recibir el mismo id.
 */
@Service
public class TareasService {

	private static final Logger log = LoggerFactory.getLogger(TareasService.class);

	/** Empieza vacía en cada arranque: no hay nada que cargar. El índice ES el orden de la tarea. */
	private final List<Tarea> tareas = new ArrayList<>();

	/** Contador monótono. Nunca decrece, así que un id no se reutiliza mientras la app siga viva. */
	private int siguienteId = Tarea.PRIMER_ID;

	public synchronized List<Tarea> consultarTodas() {
		// Copia inmutable, para que nadie pueda modificar la lista interna por la puerta de atrás.
		List<Tarea> resultado = List.copyOf(this.tareas);
		log.debug("consultarTodas() -> {} tareas", resultado.size());
		return resultado;
	}

	public synchronized Optional<Tarea> buscarPorId(int id) {
		Optional<Tarea> resultado = this.tareas.stream().filter(tarea -> tarea.id() == id).findFirst();
		log.debug("buscarPorId({}) -> {}", id, resultado.isPresent() ? "encontrada" : "no existe");
		return resultado;
	}

	/**
	 * Crea una tarea al final de la lista, que es donde el usuario espera verla aparecer.
	 *
	 * <p>El id sale de un contador propio y no del tamaño de la lista. Con {@code size()} habría
	 * colisiones: con tres tareas (1, 2, 3), al borrar la 2 el tamaño baja a 2 y la siguiente tarea
	 * recibiría el id 3, machacando una existente.
	 */
	public synchronized Tarea crear(String texto) {
		Tarea resultado = new Tarea(this.siguienteId, normalizarTexto(texto), false);
		this.tareas.add(resultado);
		this.siguienteId++;

		log.info("crear() -> creada tarea id={}", resultado.id());
		return resultado;
	}

	/**
	 * Reemplaza texto y estado conservando el id y la posición en la lista.
	 *
	 * @return la tarea actualizada, o vacío si no existe ninguna con ese id
	 */
	public synchronized Optional<Tarea> actualizar(int id, String texto, boolean completada) {
		int posicion = buscarPosicion(id);
		if (posicion == -1) {
			log.debug("actualizar({}) -> no existe", id);
			return Optional.empty();
		}

		Tarea actualizada = new Tarea(id, normalizarTexto(texto), completada);
		this.tareas.set(posicion, actualizada);

		log.info("actualizar({}) -> actualizada", id);
		return Optional.of(actualizada);
	}

	/** @return {@code true} si se ha borrado, {@code false} si no existía ninguna tarea con ese id */
	public synchronized boolean eliminar(int id) {
		int posicion = buscarPosicion(id);
		if (posicion == -1) {
			log.debug("eliminar({}) -> no existe", id);
			return false;
		}

		this.tareas.remove(posicion);
		log.info("eliminar({}) -> eliminada", id);
		return true;
	}

	/** Búsqueda lineal: con listas de tareas es más rápida que cualquier índice, y no hay que mantenerla. */
	private int buscarPosicion(int id) {
		for (int posicion = 0; posicion < this.tareas.size(); posicion++) {
			if (this.tareas.get(posicion).id() == id) {
				return posicion;
			}
		}
		return -1;
	}

	/** Quita espacios sobrantes de los extremos; {@code @NotBlank} ya ha descartado el texto vacío. */
	private String normalizarTexto(String texto) {
		String resultado = (texto == null) ? "" : texto.strip();
		return resultado;
	}

}

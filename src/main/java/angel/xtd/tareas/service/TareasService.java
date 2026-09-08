package angel.xtd.tareas.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import angel.xtd.tareas.almacen.AlmacenTareas;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.TareaNoEncontradaException;

/**
 * Lógica de negocio de la lista de tareas.
 *
 * <p>No sabe nada de HTTP ni de archivos: recibe y devuelve objetos de dominio, y lanza excepciones
 * de dominio. Traducir eso a códigos de estado es trabajo del manejador global de errores; leer y
 * escribir el JSON, del almacén.
 */
@Service
public class TareasService {

	private static final Logger log = LoggerFactory.getLogger(TareasService.class);

	private final AlmacenTareas almacen;

	public TareasService(AlmacenTareas almacen) {
		this.almacen = almacen;
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
		Tarea resultado = this.almacen.anadirAlFinal(normalizarTexto(texto));
		log.info("crear() -> creada tarea id={}", resultado.id());
		return resultado;
	}

	/** Reemplaza texto y estado conservando el id y, sobre todo, la posición en la lista. */
	public Tarea actualizar(int id, String texto, boolean completada) {
		Tarea actualizada = new Tarea(id, normalizarTexto(texto), completada);
		if (!this.almacen.reemplazar(actualizada)) {
			throw new TareaNoEncontradaException(id);
		}
		log.info("actualizar({}) -> actualizada", id);
		return actualizada;
	}

	public void eliminar(int id) {
		if (!this.almacen.eliminar(id)) {
			throw new TareaNoEncontradaException(id);
		}
		log.info("eliminar({}) -> eliminada", id);
	}

	/** Quita espacios sobrantes de los extremos; {@code @NotBlank} ya ha descartado el texto vacío. */
	private String normalizarTexto(String texto) {
		String resultado = (texto == null) ? "" : texto.strip();
		return resultado;
	}

}

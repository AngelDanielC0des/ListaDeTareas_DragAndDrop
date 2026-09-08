package angel.xtd.tareas.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de {@code PATCH /tarea/{id}/completada}.
 *
 * <p>Existe para que marcar la casilla no obligue al cliente a reenviar el texto entero, que es lo
 * que pasaría usando el {@code PUT}: menos datos en la red y ninguna posibilidad de pisar una
 * edición de texto que estuviera en curso.
 */
public record CambiarCompletadaPeticion(

		@NotNull(message = "Hay que indicar si la tarea está completada")
		Boolean completada) {

}

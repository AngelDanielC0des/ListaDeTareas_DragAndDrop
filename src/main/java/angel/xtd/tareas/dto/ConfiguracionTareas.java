package angel.xtd.tareas.dto;

/**
 * Límites del servidor que el navegador necesita conocer.
 *
 * <p>Existe para que el {@code maxlength} de los campos y el contador de caracteres no lleven el
 * número escrito a mano en el HTML y en el JavaScript. Antes el 280 estaba repetido en cinco sitios
 * y cambiarlo en el servidor no cambiaba nada en la interfaz.
 */
public record ConfiguracionTareas(int maxCaracteresTexto) {

	public static ConfiguracionTareas porDefecto() {
		ConfiguracionTareas resultado = new ConfiguracionTareas(Tarea.MAX_CARACTERES_TEXTO);
		return resultado;
	}

}

package angel.xtd.tareas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del archivo donde se persisten las tareas.
 *
 * <p>Se enlaza desde {@code application.properties} con el prefijo {@code app.almacen}. Tenerlo
 * como propiedad (y no como constante) permite que los tests apunten a un directorio temporal.
 */
@ConfigurationProperties(prefix = "app.almacen")
public record PropiedadesAlmacen(String ruta) {

	private static final String RUTA_POR_DEFECTO = "datos/tareas.json";

	public PropiedadesAlmacen {
		if (ruta == null || ruta.isBlank()) {
			ruta = RUTA_POR_DEFECTO;
		}
	}

}

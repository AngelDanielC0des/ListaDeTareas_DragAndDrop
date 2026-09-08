package angel.xtd.tareas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de los archivos donde se persisten los datos.
 *
 * <p>Se enlaza desde {@code application.properties} con el prefijo {@code app.almacen}. Tenerlo
 * como propiedad, y no como constante, permite que los tests apunten a un directorio temporal y que
 * al arrancar se pueda pasar otra ruta por línea de comandos.
 *
 * @param ruta archivo con las tareas
 * @param rutaDeFondos archivo con la asociación de cada tarea a su fondo, aparte para que
 * {@code tareas.json} conserve exactamente los tres campos de una tarea
 * @param rutaDeGrupos archivo con los grupos y a qué grupo pertenece cada tarea, aparte por el mismo
 * motivo que los fondos
 */
@ConfigurationProperties(prefix = "app.almacen")
public record PropiedadesAlmacen(String ruta, String rutaDeFondos, String rutaDeGrupos) {

	private static final String RUTA_POR_DEFECTO = "datos/tareas.json";

	private static final String RUTA_DE_FONDOS_POR_DEFECTO = "datos/fondos.json";

	private static final String RUTA_DE_GRUPOS_POR_DEFECTO = "datos/grupos.json";

	public PropiedadesAlmacen {
		if (ruta == null || ruta.isBlank()) {
			ruta = RUTA_POR_DEFECTO;
		}
		if (rutaDeFondos == null || rutaDeFondos.isBlank()) {
			rutaDeFondos = RUTA_DE_FONDOS_POR_DEFECTO;
		}
		if (rutaDeGrupos == null || rutaDeGrupos.isBlank()) {
			rutaDeGrupos = RUTA_DE_GRUPOS_POR_DEFECTO;
		}
	}

}

package angel.xtd.tareas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Punto de entrada de la aplicación.
 *
 * <p>Levanta un servidor con la API REST en {@code /tarea} y sirve la interfaz web desde
 * {@code src/main/resources/static}, las dos en el mismo puerto: al no haber dos orígenes distintos
 * no hace falta configurar CORS.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TareasApplication {

	public static void main(String[] args) {
		SpringApplication.run(TareasApplication.class, args);
	}

}

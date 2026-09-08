package angel.xtd.tareas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import angel.xtd.tareas.dto.Tarea;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

/**
 * Portada de la documentación de la API.
 *
 * <p>Sin esto, springdoc publica el esquema igual pero con el título genérico «OpenAPI definition» y
 * sin una línea que explique de qué va. La descripción se escribe aquí y no en el
 * {@code application.properties} para poder usar {@link Tarea#MAX_CARACTERES_TEXTO} en vez de
 * repetir el número: si mañana cambia el límite, este texto cambia con él.
 */
@Configuration
public class ConfiguracionOpenApi {

	@Bean
	public OpenAPI descripcionDeLaApi() {
		Info informacion = new Info().title("API de la lista de tareas")
			.version("1.0")
			.description("""
					CRUD de tareas con reordenación y fondos, sin base de datos: los datos se \
					persisten en archivos JSON.

					**Una tarea son exactamente tres campos** —`id`, `texto` y `completada`— y esa \
					restricción condiciona el resto del diseño. El orden no es un campo: lo da la \
					posición dentro del array, de modo que reordenar no cambia ningún `id` y una \
					petición en vuelo nunca acaba afectando a otra tarea. El fondo de cada tarjeta \
					tampoco es un campo: vive en su propio recurso, en `/tarea/fondo`.

					El texto admite hasta %d caracteres. Los errores se devuelven en formato \
					ProblemDetail (RFC 9457).""".formatted(Tarea.MAX_CARACTERES_TEXTO))
			.license(new License().name("MIT").url("https://opensource.org/licenses/MIT"));

		OpenAPI resultado = new OpenAPI().info(informacion);
		return resultado;
	}

}

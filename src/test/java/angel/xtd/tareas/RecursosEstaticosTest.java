package angel.xtd.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import angel.xtd.tareas.dto.Tarea;

/**
 * Comprueba que el HTML y el servidor no se contradicen.
 *
 * <p>El límite de caracteres está escrito en dos sitios: la constante
 * {@link Tarea#MAX_CARACTERES_TEXTO} y el atributo {@code maxlength} del HTML. Tenerlo en uno solo
 * exigiría un endpoint de configuración que el navegador consultase al arrancar, y esta versión
 * prefiere ahorrárselo.
 *
 * <p>La duplicación se acepta, pero no se deja sin red: estas pruebas fallan si alguien cambia uno
 * de los dos números y se olvida del otro, que es la única forma en que la duplicación hace daño.
 */
class RecursosEstaticosTest {

	private static final String RUTA_INDEX = "static/index.html";

	private static final Pattern MAXLENGTH = Pattern.compile("maxlength=\"(\\d+)\"");

	/**
	 * Se lee del classpath y no del sistema de archivos: por ruta relativa, el test dependería del
	 * directorio de trabajo y fallaría al ejecutarlo desde un IDE.
	 */
	private String leerIndex() throws IOException {
		try (InputStream flujo = getClass().getClassLoader().getResourceAsStream(RUTA_INDEX)) {
			assertThat(flujo).as("index.html debe estar en el classpath").isNotNull();
			return new String(flujo.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("todos los maxlength del HTML coinciden con Tarea.MAX_CARACTERES_TEXTO")
	void elHtmlYElServidorUsanElMismoLimite() throws IOException {
		List<Integer> limitesDelHtml = MAXLENGTH.matcher(leerIndex())
			.results()
			.map((coincidencia) -> Integer.valueOf(coincidencia.group(1)))
			.toList();

		assertThat(limitesDelHtml).as("el HTML debe declarar algún maxlength").isNotEmpty();
		assertThat(limitesDelHtml).as("cada maxlength del HTML debe coincidir con la constante del servidor")
			.containsOnly(Tarea.MAX_CARACTERES_TEXTO);
	}

	@Test
	@DisplayName("el HTML tiene la región de anuncios para lectores de pantalla")
	void elHtmlTieneRegionDeAnuncios() throws IOException {
		assertThat(leerIndex()).contains("id=\"anuncios\"").contains("aria-live=\"polite\"");
	}

	/** Sin esto, un patrón que dejara de encontrar nada haría pasar en falso la prueba de arriba. */
	@Test
	@DisplayName("el patrón de búsqueda encuentra los maxlength que hay")
	void elPatronEncuentraLosMaxlength() {
		Matcher coincidencias = MAXLENGTH.matcher("<input maxlength=\"280\"><textarea maxlength=\"280\">");

		assertThat(coincidencias.results().count()).isEqualTo(2);
	}

}

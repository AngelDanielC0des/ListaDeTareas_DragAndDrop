package angel.xtd.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprueba sobre el HTML que siguen ahí los elementos que el JavaScript da por hechos.
 *
 * <p>El frontend no tiene pruebas propias —montar un entorno de JavaScript para este proyecto sería
 * desproporcionado—, así que estas hacen de red mínima: si alguien quita uno de estos elementos al
 * retocar la maquetación, el módulo que lo busca fallaría en silencio en el navegador y aquí salta.
 */
class RecursosEstaticosTest {

	private static final String RUTA_INDEX = "static/index.html";

	private static final String RUTA_CSS = "static/css/estilos.css";

	/**
	 * Se lee del classpath y no del sistema de archivos: por ruta relativa, la prueba dependería del
	 * directorio de trabajo y fallaría al ejecutarla desde un IDE.
	 */
	private String leerRecurso(String ruta) throws IOException {
		try (InputStream flujo = getClass().getClassLoader().getResourceAsStream(ruta)) {
			assertThat(flujo).as("%s debe estar en el classpath", ruta).isNotNull();
			return new String(flujo.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String leerIndex() throws IOException {
		return leerRecurso(RUTA_INDEX);
	}

	@Test
	@DisplayName("está la región que anuncia los cambios a los lectores de pantalla")
	void tieneRegionDeAnuncios() throws IOException {
		assertThat(leerIndex()).contains("id=\"anuncios\"").contains("aria-live=\"polite\"");
	}

	@Test
	@DisplayName("están el selector de tema y la barra de progreso")
	void tieneSelectorDeTemaYProgreso() throws IOException {
		String html = leerIndex();

		assertThat(html).contains("id=\"progreso\"");
		for (String tema : new String[] { "claro", "oscuro", "sistema" }) {
			assertThat(html).as("debe existir la opción de tema «%s»", tema).contains("value=\"" + tema + "\"");
		}
	}

	/**
	 * El tema tiene que aplicarse antes del primer pintado. Si alguien mueve ese script a un módulo,
	 * seguiría funcionando pero aparecería un fogonazo del tema claro al cargar, que es justo lo que
	 * este script evita y lo que nadie recordaría al tocarlo.
	 */
	@Test
	@DisplayName("el tema se aplica en línea dentro del <head>, antes de la hoja de estilos")
	void aplicaElTemaAntesDePintar() throws IOException {
		String html = leerIndex();

		int posicionDelScript = html.indexOf("localStorage.getItem('tareas.tema')");
		int posicionDeLosEstilos = html.indexOf("css/estilos.css");

		assertThat(posicionDelScript).as("debe existir el script que aplica el tema").isNotNegative();
		assertThat(posicionDelScript).as("debe ir antes de la hoja de estilos").isLessThan(posicionDeLosEstilos);
	}

	@Test
	@DisplayName("está el aviso de deshacer que sustituye al confirm() del navegador")
	void tieneAvisoDeDeshacer() throws IOException {
		assertThat(leerIndex()).contains("id=\"aviso-deshacer\"").contains("id=\"boton-deshacer\"");
	}

	/**
	 * Regresión de un fallo que estuvo ahí desde el principio y no se veía en ninguna prueba.
	 *
	 * <p>El navegador oculta lo marcado con {@code hidden} mediante una regla suya, y cualquier
	 * {@code display} que declare la hoja de estilos le gana. Como {@code .tarea__texto} lleva
	 * {@code -webkit-box} para recortar a dos líneas, al editar se veían a la vez el párrafo y el
	 * cuadro de edición. Lo mismo le pasaba al aviso de deshacer, que es {@code flex}.
	 */
	@Test
	@DisplayName("la hoja de estilos reafirma el atributo hidden")
	void reafirmaElAtributoHidden() throws IOException {
		String css = leerRecurso(RUTA_CSS);

		assertThat(css).as("hace falta una regla [hidden] que gane a los display de las clases")
			.contains("[hidden]")
			.contains("display: none !important");
	}

	@Test
	@DisplayName("están el enlace de salto y el selector de fondo")
	void tieneSaltoYSelectorDeFondo() throws IOException {
		String html = leerIndex();

		assertThat(html).as("el enlace de salto deja evitar la cabecera con el tabulador")
			.contains("salto-al-contenido");
		assertThat(html).as("el selector usa <dialog>, que trae el foco atrapado y el cierre con Escape")
			.contains("<dialog").contains("id=\"selector-fondo\"");
	}

	/**
	 * Los iconos son decorativos: el nombre del botón sale de su texto o de su aria-label. Un SVG sin
	 * aria-hidden se anunciaría además del nombre, duplicando lo que oye el usuario.
	 */
	@Test
	@DisplayName("todos los iconos están ocultos para los lectores de pantalla")
	void losIconosSonDecorativos() throws IOException {
		String html = leerIndex();

		int iconos = html.split("<svg", -1).length - 1;
		int ocultos = html.split("aria-hidden=\"true\"", -1).length - 1;

		assertThat(iconos).as("debe haber iconos que comprobar").isPositive();
		assertThat(ocultos).as("cada <svg> debe llevar aria-hidden").isGreaterThanOrEqualTo(iconos);
	}

	@Test
	@DisplayName("existen los cinco archivos de imagen de fondo")
	void existenLasCincoImagenes() {
		for (String nombre : new String[] { "ondas", "puntos", "lineas", "papel", "aurora" }) {
			assertThat(getClass().getClassLoader().getResource("static/img/" + nombre + ".svg"))
				.as("falta la imagen de fondo «%s»", nombre)
				.isNotNull();
		}
	}

}

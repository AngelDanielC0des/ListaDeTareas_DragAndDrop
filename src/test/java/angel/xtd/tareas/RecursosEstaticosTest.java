package angel.xtd.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprueba sobre el HTML que siguen ahí los elementos que el JavaScript da por hechos.
 *
 * <p>El frontend tiene sus propias pruebas en {@code src/test/js/}, que cargan este mismo HTML en
 * jsdom y comprueban el comportamiento de verdad. Estas se quedan igualmente por dos motivos: hacen
 * que un {@code ./mvnw verify} a secas detecte la deriva entre el HTML y el JavaScript sin necesitar
 * Node instalado, y cubren cosas que desde el navegador no se ven, como que el script del tema esté
 * antes de la hoja de estilos o que existan los archivos de imagen.
 */
class RecursosEstaticosTest {

	private static final String RUTA_INDEX = "static/index.html";

	private static final String RUTA_CSS = "static/css/estilos.css";

	/** La etiqueta de apertura de cada SVG, para poder comprobarlas una a una y no en bloque. */
	private static final Pattern ETIQUETA_SVG = Pattern.compile("<svg[^>]*>");

	/** Los comentarios del HTML, que hablan de {@code <svg>} sin ser ninguno. */
	private static final Pattern COMENTARIO_HTML = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

	/** La clave de localStorage tal y como la escribe el script en línea del HTML. */
	private static final Pattern CLAVE_DEL_TEMA = Pattern.compile("localStorage\\.getItem\\('([^']+)'\\)");

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
		List<String> etiquetasSvg = buscarEtiquetasSvg(leerIndex());

		assertThat(etiquetasSvg).as("debe haber iconos que comprobar").isNotEmpty();
		assertThat(etiquetasSvg)
			.as("cada <svg> debe llevar su propio aria-hidden, no vale que sumen otros elementos")
			.allSatisfy((etiqueta) -> assertThat(etiqueta).contains("aria-hidden=\"true\""));
	}

	/**
	 * Sin esto, la prueba de arriba pasaría en falso el día que el patrón dejara de encontrar nada.
	 *
	 * <p>Su versión anterior contaba apariciones de {@code <svg} y de {@code aria-hidden} en todo el
	 * archivo y comparaba las dos cifras. Como hay otros elementos con {@code aria-hidden} —el
	 * {@code <progress>} y el símbolo del asa en el pie—, sobraban dos, y con esa holgura un icono
	 * podía perder el atributo sin que la prueba se enterase.
	 */
	@Test
	@DisplayName("el patrón encuentra cada etiqueta <svg> por separado")
	void elPatronDeIconosEncuentraCadaEtiqueta() {
		String html = "<!-- el <svg aria-hidden> del comentario no cuenta -->"
				+ "<svg class=\"a\" aria-hidden=\"true\"><path/></svg><svg class=\"b\"></svg>";

		List<String> etiquetas = buscarEtiquetasSvg(html);

		assertThat(etiquetas).as("los comentarios no son elementos").hasSize(2);
		assertThat(etiquetas.get(1)).as("debe poder detectar un svg SIN aria-hidden")
			.doesNotContain("aria-hidden");
	}

	/**
	 * Las etiquetas {@code <svg>} de verdad, sin las que solo se nombran en un comentario.
	 *
	 * <p>Los comentarios se quitan antes de buscar porque el HTML explica en uno de ellos que «el
	 * signo va en un {@code <svg aria-hidden>}», y esa frase se colaba como si fuera un icono real.
	 */
	private List<String> buscarEtiquetasSvg(String html) {
		String sinComentarios = COMENTARIO_HTML.matcher(html).replaceAll("");
		List<String> resultado = ETIQUETA_SVG.matcher(sinComentarios).results().map(MatchResult::group).toList();
		return resultado;
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

	/**
	 * La clave está escrita en dos sitios —el script en línea del HTML y {@code preferencias.js}— y no
	 * se puede unificar: el script tiene que correr antes de que se cargue ningún módulo. Lo que sí se
	 * puede es no dejar la duplicación sin vigilancia.
	 *
	 * <p>La clave se extrae del HTML en lugar de repetirla aquí: si se escribiera a mano, cambiar los
	 * dos archivos y olvidar la prueba la dejaría comprobando una cadena que ya no usa nadie.
	 */
	@Test
	@DisplayName("la clave de localStorage del tema es la misma en el HTML y en preferencias.js")
	void laClaveDelTemaCoincideEnLosDosArchivos() throws IOException {
		Matcher clave = CLAVE_DEL_TEMA.matcher(leerIndex());

		assertThat(clave.find()).as("el script en línea debe leer la preferencia de localStorage").isTrue();
		assertThat(leerRecurso("static/js/preferencias.js"))
			.as("preferencias.js debe usar la misma clave «%s» que el HTML", clave.group(1))
			.contains("'" + clave.group(1) + "'");
	}

	/**
	 * {@code vista.js} exige estos dos elementos al cargar —{@code exigirElemento} lanza si faltan—,
	 * así que quitarlos del HTML dejaría la aplicación sin arrancar en el navegador. Aquí salta antes.
	 */
	@Test
	@DisplayName("están el estado vacío y la plantilla de la tarjeta que el JavaScript exige")
	void tieneEstadoVacioYPlantilla() throws IOException {
		String html = leerIndex();

		assertThat(html).as("vista.js busca #lista-vacia para enseñarlo cuando no hay tareas")
			.contains("id=\"lista-vacia\"");
		assertThat(html).as("las tarjetas se clonan de esta plantilla, no se construyen con cadenas")
			.contains("id=\"plantilla-tarea\"")
			.contains("class=\"tarea__accion-texto\"");
	}

}

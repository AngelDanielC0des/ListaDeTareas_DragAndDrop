package angel.xtd.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import angel.xtd.tareas.almacen.AlmacenTareas;
import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.controller.TareasController;
import angel.xtd.tareas.error.ManejadorErroresGlobal;
import angel.xtd.tareas.service.TareasService;

/**
 * Comprueba que la aplicación arranca de verdad.
 *
 * <p>Es la única prueba que levanta el contexto completo. Las demás son de rebanada o unitarias:
 * {@code @WebMvcTest} carga solo la capa web con el servicio simulado, y el resto construye las
 * clases con {@code new}. Sin esta, <b>nada comprobaría que los beans se conectan entre sí</b>: si
 * {@code AlmacenTareas} fallara al construirse, si {@code @ConfigurationPropertiesScan} dejara de
 * encontrar las propiedades o si apareciera una dependencia circular, el resto seguiría en verde y
 * la aplicación no arrancaría.
 *
 * <p><b>Las tres rutas del almacén</b> se mandan a un directorio temporal del sistema: con las de por
 * defecto, cada ejecución de las pruebas escribiría dentro de {@code datos/} del proyecto y, peor
 * aún, leería los datos reales de quien la ejecuta, de modo que el resultado dependería de lo que
 * ese alguien tuviera guardado. Falta una y la prueba deja de estar aislada, que es justo lo que le
 * pasó a {@code ruta-de-grupos} al añadirla.
 */
@SpringBootTest(properties = {
	"app.almacen.ruta=${java.io.tmpdir}/tareas-test-contexto/tareas.json",
	"app.almacen.ruta-de-fondos=${java.io.tmpdir}/tareas-test-contexto/fondos.json",
	"app.almacen.ruta-de-grupos=${java.io.tmpdir}/tareas-test-contexto/grupos.json"
})
class TareasApplicationTest {

	/**
	 * Se filtra por paquete y no se cuentan todos los advices del contexto: springdoc registra el
	 * suyo para documentar la API, y contar el total haría fallar esta prueba por una dependencia
	 * que no tiene nada que ver con lo que aquí se comprueba.
	 */
	private static final String PAQUETE_RAIZ = "angel.xtd.tareas";

	@Autowired
	private ApplicationContext contexto;

	@Test
	@DisplayName("el contexto de Spring arranca y todos los beans quedan conectados")
	void elContextoArranca() {
		assertThat(contexto).isNotNull();
	}

	/**
	 * Que el contexto arranque no garantiza que estén los beans que importan: bastaría con que una
	 * clase perdiera su anotación de estereotipo para que dejara de registrarse en silencio.
	 */
	@Test
	@DisplayName("están registrados los beans de las tres capas y el manejador de errores")
	void estanLosBeansDeCadaCapa() {
		assertThat(contexto.getBean(TareasController.class)).isNotNull();
		assertThat(contexto.getBean(TareasService.class)).isNotNull();
		assertThat(contexto.getBean(AlmacenTareas.class)).isNotNull();
		assertThat(contexto.getBean(ManejadorErroresGlobal.class)).isNotNull();
	}

	/**
	 * Regresión del refactor del manejo de errores: al extender {@code ResponseEntityExceptionHandler}
	 * hay que evitar declarar un {@code @ExceptionHandler} para una excepción que la clase padre ya
	 * cubre, porque Spring lo rechaza como mapeo ambiguo <b>al construir el contexto</b>. Si eso
	 * ocurriera, este arranque fallaría antes de llegar a la comprobación.
	 */
	@Test
	@DisplayName("el manejador de errores no declara mapeos ambiguos")
	void elManejadorDeErroresSeRegistraSinAmbiguedades() {
		Map<String, Object> propios = contexto.getBeansWithAnnotation(RestControllerAdvice.class)
			.entrySet()
			.stream()
			.filter((bean) -> bean.getValue().getClass().getPackageName().startsWith(PAQUETE_RAIZ))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(propios).as("debe haber exactamente un advice nuestro").hasSize(1);
	}

	@Test
	@DisplayName("arrancar no crea ninguno de los archivos de datos hasta que hay algo que guardar")
	void noCreaLosArchivosAlArrancar() {
		Path temporal = Path.of(System.getProperty("java.io.tmpdir"), "tareas-test-contexto");

		for (String nombre : new String[] { "tareas.json", "fondos.json", "grupos.json" }) {
			assertThat(Files.exists(temporal.resolve(nombre)))
				.as("%s: un almacén solo debe escribir cuando se modifica algo", nombre)
				.isFalse();
		}
	}

	/**
	 * Guarda de aislamiento, y no una comprobación de la configuración por sí misma.
	 *
	 * <p>La prueba de arriba no basta: si una ruta se quedara apuntando a {@code datos/} del
	 * proyecto, el archivo tampoco aparecería en el temporal y todo seguiría en verde mientras la
	 * prueba lee y escribe los datos reales. Aquí se comprueba lo que de verdad importa, que las tres
	 * rutas caen dentro del directorio temporal.
	 */
	@Test
	@DisplayName("las tres rutas del almacén apuntan al directorio temporal, no al del proyecto")
	void ningunAlmacenTocaLosDatosDelProyecto() {
		PropiedadesAlmacen propiedades = this.contexto.getBean(PropiedadesAlmacen.class);
		String temporal = Path.of(System.getProperty("java.io.tmpdir"), "tareas-test-contexto").toString();

		assertThat(List.of(propiedades.ruta(), propiedades.rutaDeFondos(), propiedades.rutaDeGrupos()))
			.as("una ruta fuera del temporal deja la prueba leyendo y escribiendo los datos de verdad")
			.allSatisfy((ruta) -> assertThat(Path.of(ruta).toString()).startsWith(temporal));
	}

}

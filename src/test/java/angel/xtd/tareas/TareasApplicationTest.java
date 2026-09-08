package angel.xtd.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import angel.xtd.tareas.almacen.AlmacenTareas;
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
 * <p>La ruta del almacén se manda a un directorio temporal del sistema: con la de por defecto, cada
 * ejecución de las pruebas crearía un {@code datos/tareas.json} dentro del proyecto.
 */
@SpringBootTest(properties = {
	"app.almacen.ruta=${java.io.tmpdir}/tareas-test-contexto/tareas.json",
	"app.almacen.ruta-de-fondos=${java.io.tmpdir}/tareas-test-contexto/fondos.json"
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
	@DisplayName("arrancar no crea el archivo de datos hasta que hay algo que guardar")
	void noCreaElArchivoAlArrancar() {
		Path archivo = Path.of(System.getProperty("java.io.tmpdir"), "tareas-test-contexto", "tareas.json");

		assertThat(Files.exists(archivo)).as("el almacén solo debe escribir cuando se modifica algo").isFalse();
	}

}

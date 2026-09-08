package angel.xtd.tareas.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Punto ÚNICO de traducción de excepciones a respuestas HTTP.
 *
 * <p>La alternativa —un {@code try/catch} por método de controlador— duplica código, produce
 * códigos de estado inconsistentes entre endpoints, es fácil olvidarse de uno y mezcla la
 * fontanería de errores con la lógica de negocio. Aquí el controlador escribe solo el camino feliz,
 * el servicio lanza excepciones de dominio con significado, y esta clase es la única que sabe de
 * HTTP. Añadir un endpoint nuevo no requiere escribir ni una línea de manejo de errores.
 *
 * <p>Todas las respuestas usan el mismo formato, {@link ProblemDetail} (RFC 9457), para que el
 * frontend tenga un solo parser en lugar de uno por endpoint.
 *
 * <h2>Por qué extiende ResponseEntityExceptionHandler</h2>
 * Es el punto de extensión que el framework diseñó para esto, y usarlo no es una preferencia de
 * estilo: la autoconfiguración de Spring Boot lleva
 * {@code @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)}, de modo que al declarar
 * esta subclase el manejador de Boot se aparta solo y este pasa a atender también las excepciones
 * del framework (405, 415, 404 de recurso...).
 *
 * <p>Eso resuelve de raíz un problema que antes había que sostener a base de avisos. Con dos
 * {@code @ControllerAdvice} separados, Spring se queda con el primero que tenga <b>cualquier</b>
 * método aplicable; como {@code Exception} casa con todo, una red de seguridad en el advice de
 * prioridad alta dejaba sin ejecutar al de Boot y convertía los 405 y 415 en 500. Dentro de una
 * <b>misma</b> clase manda el manejador más específico, así que aquí {@link #manejarInesperado} es
 * inofensivo y no hace falta ningún {@code @Order}.
 *
 * <p>Como {@code ResponseEntityExceptionHandler} implementa {@code MessageSourceAware}, los textos
 * de las excepciones del framework se siguen traduciendo en {@code messages.properties}.
 */
@RestControllerAdvice
public class ManejadorErroresGlobal extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ManejadorErroresGlobal.class);

	/**
	 * Prefijo de los identificadores de tipo de error.
	 *
	 * <p>Se usa un URN y no una URL: la RFC 9457 solo pide un URI que identifique el tipo de
	 * problema, y apuntar a un {@code https://} de un dominio que no existe promete una
	 * documentación que nadie puede consultar.
	 */
	private static final String BASE_TIPOS_ERROR = "urn:tareas:error:";

	/* ------------------------------------------------- Excepciones del dominio */

	@ExceptionHandler(TareaNoEncontradaException.class)
	public ProblemDetail manejarTareaNoEncontrada(TareaNoEncontradaException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.NOT_FOUND, "Tarea no encontrada",
				excepcion.getMessage(), "tarea-no-encontrada");
		resultado.setProperty("id", excepcion.getId());
		log.warn("404 Tarea no encontrada: {}", excepcion.getMessage());
		return resultado;
	}

	@ExceptionHandler(OrdenInvalidoException.class)
	public ProblemDetail manejarOrdenInvalido(OrdenInvalidoException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.CONFLICT, "Orden inválido",
				excepcion.getMessage(), "orden-invalido");
		log.warn("409 Orden inválido: {}", excepcion.getMessage());
		return resultado;
	}

	@ExceptionHandler(AlmacenamientoException.class)
	public ProblemDetail manejarAlmacenamiento(AlmacenamientoException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.INTERNAL_SERVER_ERROR, "Error de almacenamiento",
				"No se han podido guardar los cambios. Inténtalo de nuevo.", "almacenamiento");
		log.error("500 Error de almacenamiento", excepcion);
		return resultado;
	}

	/**
	 * Ruta con un tipo que no encaja, por ejemplo {@code GET /tarea/abc}.
	 *
	 * <p>Se declara aquí y no se sobrescribe nada porque esta excepción concreta <b>no</b> está en la
	 * lista de la clase padre —solo su supertipo {@code TypeMismatchException}—, y al ser más
	 * específica gana sin provocar un mapeo ambiguo.
	 *
	 * <p>El detalle nombra el parámetro y el tipo que se esperaba, pero NO devuelve el valor
	 * recibido: sería reflejar entrada del usuario en la respuesta sin ninguna necesidad.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail manejarTipoIncorrecto(MethodArgumentTypeMismatchException excepcion) {
		String tipoEsperado = (excepcion.getRequiredType() == null) ? "válido"
				: excepcion.getRequiredType().getSimpleName();

		ProblemDetail resultado = construirProblema(HttpStatus.BAD_REQUEST, "Parámetro inválido",
				"El parámetro «%s» debe ser de tipo %s.".formatted(excepcion.getName(), tipoEsperado),
				"parametro-invalido");
		log.warn("400 Parámetro inválido: {} (se esperaba {})", excepcion.getName(), tipoEsperado);
		return resultado;
	}

	/**
	 * Red de seguridad para lo que nadie más haya atendido.
	 *
	 * <p>Aquí es inofensiva: dentro de una misma clase Spring elige el manejador más específico, así
	 * que las excepciones del framework siguen yendo a los métodos heredados. En un
	 * {@code @ControllerAdvice} aparte con prioridad alta, en cambio, se las tragaría todas.
	 *
	 * <p>El detalle que se devuelve es genérico a propósito: el mensaje real puede filtrar rutas de
	 * archivos o estructura interna, así que ese va al log y no a la respuesta.
	 */
	@ExceptionHandler(Exception.class)
	public ProblemDetail manejarInesperado(Exception excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.INTERNAL_SERVER_ERROR, "Error inesperado",
				"Ha ocurrido un error inesperado en el servidor.", "inesperado");
		log.error("500 Error inesperado", excepcion);
		return resultado;
	}

	/* ---------------------------------------- Excepciones del framework, afinadas */

	/**
	 * Añade a la respuesta de validación el detalle campo a campo.
	 *
	 * <p>Se sobrescribe el método de la clase padre en vez de declarar un {@code @ExceptionHandler}
	 * propio porque {@link MethodArgumentNotValidException} ya está en su lista, y declararla otra vez
	 * sería un mapeo ambiguo que revienta al arrancar.
	 *
	 * <p>Se delega primero en {@code super} para aprovechar el cuerpo que ya construye —con el título
	 * y el detalle traducidos desde {@code messages.properties}— y solo se le añade lo que falta.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException excepcion,
			HttpHeaders cabeceras, HttpStatusCode estado, WebRequest peticion) {

		Map<String, String> errores = new LinkedHashMap<>();
		excepcion.getBindingResult()
			.getFieldErrors()
			.forEach((error) -> errores.putIfAbsent(error.getField(), error.getDefaultMessage()));

		ResponseEntity<Object> respuesta = super.handleMethodArgumentNotValid(excepcion, cabeceras, estado, peticion);
		if (respuesta != null && respuesta.getBody() instanceof ProblemDetail problema) {
			problema.setType(URI.create(BASE_TIPOS_ERROR + "validacion"));
			problema.setProperty("errores", errores);
		}

		log.warn("400 Validación fallida: {}", errores);
		return respuesta;
	}

	private ProblemDetail construirProblema(HttpStatus estado, String titulo, String detalle, String tipo) {
		ProblemDetail resultado = ProblemDetail.forStatusAndDetail(estado, detalle);
		resultado.setTitle(titulo);
		resultado.setType(URI.create(BASE_TIPOS_ERROR + tipo));
		return resultado;
	}

}

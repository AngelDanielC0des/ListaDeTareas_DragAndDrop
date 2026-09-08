package angel.xtd.tareas.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Punto ÚNICO de traducción de excepciones del dominio a respuestas HTTP.
 *
 * <p>La alternativa —un {@code try/catch} por método de controlador— duplica código, produce
 * códigos de estado inconsistentes entre endpoints, es fácil olvidarse de uno y mezcla la
 * fontanería de errores con la lógica de negocio. Aquí el controlador escribe solo el camino feliz,
 * el servicio lanza excepciones de dominio con significado, y esta clase es la única que sabe de
 * HTTP. Añadir un endpoint nuevo no requiere escribir ni una línea de manejo de errores.
 *
 * <p>Todas las respuestas usan el mismo formato, {@link ProblemDetail} (RFC 9457), para que el
 * frontend tenga un solo parser en lugar de uno por endpoint. Los errores que no pasan por aquí
 * —los que genera el propio Spring, como el 405 o el 415— salen igualmente en español porque sus
 * textos están traducidos en {@code messages.properties}.
 *
 * <h2>Por qué la prioridad máxima</h2>
 * Con {@code spring.mvc.problemdetails.enabled=true}, Spring Boot registra su propio
 * {@code @ControllerAdvice} para que las excepciones del framework (405, 415, 404 de recurso...)
 * también respondan en formato ProblemDetail. Ese manejador cubre, entre otras,
 * {@link MethodArgumentNotValidException}, y sin ordenar el nuestro ganaba el suyo: las validaciones
 * respondían un «Bad Request» genérico sin el detalle por campo.
 *
 * <h2>NO añadir aquí un manejador de Exception</h2>
 * Puede parecer que falta una red de seguridad que atrape cualquier excepción, pero <b>añadirla aquí
 * rompería la aplicación de una forma nada evidente</b>. Spring se queda con el primer advice que
 * tenga <b>cualquier</b> método aplicable, y {@code Exception} casa con todo: un catch-all en una
 * clase con la prioridad máxima dejaría sin ejecutar al manejador de Boot y convertiría todos los
 * 405 y 415 en 500. Si alguna vez hace falta esa red de seguridad, tiene que vivir en otra clase con
 * {@code @Order(Ordered.LOWEST_PRECEDENCE)}. Hay tests que cubren el 405 y el 415 y saltarían.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ManejadorErroresGlobal {

	private static final Logger log = LoggerFactory.getLogger(ManejadorErroresGlobal.class);

	/**
	 * Prefijo de los identificadores de tipo de error.
	 *
	 * <p>Se usa un URN y no una URL: la RFC 9457 solo pide un URI que identifique el tipo de
	 * problema, y apuntar a un {@code https://} de un dominio que no existe promete una
	 * documentación que nadie puede consultar.
	 */
	private static final String BASE_TIPOS_ERROR = "urn:tareas:error:";

	@ExceptionHandler(TareaNoEncontradaException.class)
	public ProblemDetail manejarTareaNoEncontrada(TareaNoEncontradaException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.NOT_FOUND, "Tarea no encontrada",
				excepcion.getMessage(), "tarea-no-encontrada");
		resultado.setProperty("id", excepcion.getId());
		log.warn("404 Tarea no encontrada: {}", excepcion.getMessage());
		return resultado;
	}

	/** La lanza {@code @Valid} cuando un DTO de petición incumple sus restricciones. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail manejarValidacion(MethodArgumentNotValidException excepcion) {
		Map<String, String> errores = new LinkedHashMap<>();
		excepcion.getBindingResult()
				.getFieldErrors()
				.forEach(error -> errores.putIfAbsent(error.getField(), error.getDefaultMessage()));

		ProblemDetail resultado = construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
				"Revisa los campos indicados en la propiedad «errores».", "validacion");
		resultado.setProperty("errores", errores);
		log.warn("400 Validación fallida: {}", errores);
		return resultado;
	}

	/**
	 * Ruta con un tipo que no encaja, por ejemplo {@code GET /tarea/abc}.
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

	/** Cuerpo ausente o JSON mal formado. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail manejarCuerpoIlegible(HttpMessageNotReadableException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.BAD_REQUEST, "Cuerpo ilegible",
				"El cuerpo de la petición falta o no es JSON válido.", "cuerpo-ilegible");
		log.warn("400 Cuerpo ilegible: {}", excepcion.getMessage());
		return resultado;
	}

	@ExceptionHandler(AlmacenamientoException.class)
	public ProblemDetail manejarAlmacenamiento(AlmacenamientoException excepcion) {
		ProblemDetail resultado = construirProblema(HttpStatus.INTERNAL_SERVER_ERROR, "Error de almacenamiento",
				"No se han podido guardar los cambios. Inténtalo de nuevo.", "almacenamiento");
		log.error("500 Error de almacenamiento", excepcion);
		return resultado;
	}

	private ProblemDetail construirProblema(HttpStatus estado, String titulo, String detalle, String tipo) {
		ProblemDetail resultado = ProblemDetail.forStatusAndDetail(estado, detalle);
		resultado.setTitle(titulo);
		resultado.setType(URI.create(BASE_TIPOS_ERROR + tipo));
		return resultado;
	}

}

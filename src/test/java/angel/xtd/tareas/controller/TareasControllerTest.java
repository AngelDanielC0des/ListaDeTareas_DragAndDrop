package angel.xtd.tareas.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.TareaNoEncontradaException;
import angel.xtd.tareas.service.TareasService;

/**
 * Pruebas de la capa web: rutas, códigos de estado y traducción de excepciones.
 *
 * <p>El servicio va simulado a propósito: lo que se comprueba aquí es el contrato HTTP, no la
 * lógica de negocio (que tiene sus propias pruebas).
 */
@WebMvcTest(TareasController.class)
class TareasControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TareasService servicio;

	@Test
	@DisplayName("GET /tarea devuelve la lista en el orden del servicio")
	void listaTareas() throws Exception {
		given(this.servicio.consultarTodas())
			.willReturn(List.of(new Tarea(7, "Repasar JS", false), new Tarea(2, "Repasar CSS", true)));

		this.mockMvc.perform(get("/tarea"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(7))
			.andExpect(jsonPath("$[1].id").value(2));
	}

	@Test
	@DisplayName("POST /tarea responde 201 con la cabecera Location")
	void creaTarea() throws Exception {
		given(this.servicio.crear(anyString())).willReturn(new Tarea(1, "Repasar HTML", false));

		this.mockMvc
			.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"Repasar HTML\"}"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/tarea/1"))
			.andExpect(jsonPath("$.texto").value("Repasar HTML"));
	}

	@Test
	@DisplayName("POST /tarea con texto vacío responde 400 con el detalle del campo")
	void rechazaTextoVacio() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errores.texto").exists());

		then(this.servicio).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("POST /tarea con más de 280 caracteres responde 400")
	void rechazaTextoDemasiadoLargo() throws Exception {
		String demasiado = "a".repeat(Tarea.MAX_CARACTERES_TEXTO + 1);

		this.mockMvc
			.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + demasiado + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errores.texto").exists());
	}

	@Test
	@DisplayName("DELETE de una tarea existente responde 204")
	void eliminaTarea() throws Exception {
		this.mockMvc.perform(delete("/tarea/1")).andExpect(status().isNoContent());

		then(this.servicio).should().eliminar(1);
	}

	@Test
	@DisplayName("una tarea inexistente se traduce a 404 con formato ProblemDetail")
	void traduceNoEncontradaA404() throws Exception {
		willThrow(new TareaNoEncontradaException(99)).given(this.servicio).eliminar(99);

		this.mockMvc.perform(delete("/tarea/99"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("Tarea no encontrada"))
			.andExpect(jsonPath("$.id").value(99));
	}

	@Test
	@DisplayName("un id que no es un número responde 400 en lugar de 500")
	void traduceIdNoNumericoA400() throws Exception {
		this.mockMvc.perform(get("/tarea/abc")).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("un cuerpo que no es JSON válido responde 400")
	void rechazaCuerpoIlegible() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON).content("{esto no es json"))
			.andExpect(status().isBadRequest());
	}

	/**
	 * Regresión: el manejador de errores llegó a tener un {@code @ExceptionHandler(Exception.class)}
	 * con prioridad máxima, lo que dejaba sin ejecutar al de Spring Boot y convertía este 405 en un
	 * 500. Por eso la red de seguridad vive en un advice aparte con la prioridad mínima.
	 */
	@Test
	@DisplayName("un método no soportado responde 405, no 500")
	void devuelve405ConMetodoNoSoportado() throws Exception {
		this.mockMvc.perform(patch("/tarea").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	@DisplayName("un tipo de contenido que no es JSON responde 415, no 500")
	void devuelve415ConTipoDeContenidoNoJson() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.TEXT_PLAIN).content("Repasar HTML"))
			.andExpect(status().isUnsupportedMediaType());
	}

}

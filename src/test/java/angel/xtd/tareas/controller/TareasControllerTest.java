package angel.xtd.tareas.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.service.TareasService;

/**
 * Pruebas de la capa web: rutas, códigos de estado y formato JSON.
 *
 * <p>El servicio va simulado a propósito: lo que se comprueba aquí es el contrato HTTP, no la lógica
 * de negocio (que tiene sus propias pruebas).
 */
@WebMvcTest(TareasController.class)
class TareasControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TareasService servicio;

	@Test
	@DisplayName("GET /tarea devuelve la lista en JSON")
	void listaTareas() throws Exception {
		given(this.servicio.consultarTodas())
			.willReturn(List.of(new Tarea(1, "Repasar HTML", false), new Tarea(2, "Repasar CSS", true)));

		this.mockMvc.perform(get("/tarea"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(1))
			.andExpect(jsonPath("$[0].texto").value("Repasar HTML"))
			.andExpect(jsonPath("$[1].completada").value(true));
	}

	@Test
	@DisplayName("GET /tarea devuelve una lista vacía al arrancar")
	void listaVaciaAlArrancar() throws Exception {
		given(this.servicio.consultarTodas()).willReturn(List.of());

		this.mockMvc.perform(get("/tarea")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	@DisplayName("GET /tarea/{id} devuelve la tarea pedida")
	void consultaUnaTarea() throws Exception {
		given(this.servicio.buscarPorId(1)).willReturn(Optional.of(new Tarea(1, "Repasar HTML", false)));

		this.mockMvc.perform(get("/tarea/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.texto").value("Repasar HTML"));
	}

	@Test
	@DisplayName("GET /tarea/{id} de una tarea que no existe responde 404")
	void consultaInexistenteDa404() throws Exception {
		given(this.servicio.buscarPorId(9999)).willReturn(Optional.empty());

		this.mockMvc.perform(get("/tarea/9999")).andExpect(status().isNotFound());
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
	@DisplayName("POST /tarea con texto vacío responde 400")
	void rechazaTextoVacio() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"  \"}"))
			.andExpect(status().isBadRequest());

		then(this.servicio).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("POST /tarea con más de 280 caracteres responde 400")
	void rechazaTextoDemasiadoLargo() throws Exception {
		String demasiado = "a".repeat(Tarea.MAX_CARACTERES_TEXTO + 1);

		this.mockMvc
			.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + demasiado + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PUT /tarea/{id} actualiza texto y estado")
	void actualizaTarea() throws Exception {
		given(this.servicio.actualizar(anyInt(), anyString(), anyBoolean()))
			.willReturn(Optional.of(new Tarea(1, "Repasar HTML a fondo", true)));

		this.mockMvc
			.perform(put("/tarea/1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"Repasar HTML a fondo\",\"completada\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.texto").value("Repasar HTML a fondo"))
			.andExpect(jsonPath("$.completada").value(true));
	}

	@Test
	@DisplayName("PUT de una tarea que no existe responde 404")
	void actualizaInexistenteDa404() throws Exception {
		given(this.servicio.actualizar(anyInt(), anyString(), anyBoolean())).willReturn(Optional.empty());

		this.mockMvc
			.perform(put("/tarea/9999").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"Da igual\",\"completada\":false}"))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("PUT sin el campo completada responde 400")
	void rechazaCompletadaAusente() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"Solo texto\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("DELETE de una tarea existente responde 204")
	void eliminaTarea() throws Exception {
		given(this.servicio.eliminar(1)).willReturn(true);

		this.mockMvc.perform(delete("/tarea/1")).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("DELETE de una tarea que no existe responde 404")
	void eliminaInexistenteDa404() throws Exception {
		given(this.servicio.eliminar(9999)).willReturn(false);

		this.mockMvc.perform(delete("/tarea/9999")).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("un id que no es un número responde 400 en lugar de 500")
	void idNoNumericoDa400() throws Exception {
		this.mockMvc.perform(get("/tarea/abc")).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("un cuerpo que no es JSON válido responde 400")
	void rechazaCuerpoIlegible() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.APPLICATION_JSON).content("{esto no es json"))
			.andExpect(status().isBadRequest());
	}

	/**
	 * Al no haber ningún {@code @ControllerAdvice} propio, de esto se encarga Spring Boot. El test
	 * está para que salte si alguna vez se añade uno con un {@code @ExceptionHandler(Exception)} de
	 * prioridad alta, que se tragaría estas excepciones y las convertiría en 500.
	 */
	@Test
	@DisplayName("un método no soportado responde 405, no 500")
	void metodoNoSoportadoDa405() throws Exception {
		this.mockMvc.perform(patch("/tarea").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	@DisplayName("un tipo de contenido que no es JSON responde 415, no 500")
	void tipoDeContenidoNoJsonDa415() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.TEXT_PLAIN).content("Repasar HTML"))
			.andExpect(status().isUnsupportedMediaType());
	}

}

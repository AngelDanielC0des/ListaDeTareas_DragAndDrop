package angel.xtd.tareas.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.OrdenInvalidoException;
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
	@DisplayName("PATCH sin el campo completada responde 400")
	void rechazaCompletadaAusente() throws Exception {
		this.mockMvc.perform(patch("/tarea/1/completada").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errores.completada").exists());
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
	@DisplayName("PUT /tarea/orden aplica el nuevo orden")
	void reordena() throws Exception {
		given(this.servicio.reordenar(anyList()))
			.willReturn(List.of(new Tarea(3, "Tres", false), new Tarea(1, "Uno", false)));

		this.mockMvc
			.perform(put("/tarea/orden").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[3,1]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(3))
			.andExpect(jsonPath("$[1].id").value(1));
	}

	@Test
	@DisplayName("/tarea/orden no se confunde con /tarea/{id}")
	void laRutaOrdenTienePrioridadSobreLaPlantilla() throws Exception {
		given(this.servicio.reordenar(anyList())).willReturn(List.of());

		this.mockMvc.perform(put("/tarea/orden").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1]}"))
			.andExpect(status().isOk());

		then(this.servicio).should().reordenar(List.of(1));
		then(this.servicio).should(never()).actualizar(anyInt(), anyString(), anyBoolean());
	}

	@Test
	@DisplayName("un orden inválido se traduce a 409")
	void traduceOrdenInvalidoA409() throws Exception {
		given(this.servicio.reordenar(anyList())).willThrow(new OrdenInvalidoException("ids desconocidos"));

		this.mockMvc.perform(put("/tarea/orden").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1,2]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.title").value("Orden inválido"));
	}

	@Test
	@DisplayName("PUT /tarea/orden con la lista vacía responde 400")
	void rechazaOrdenVacio() throws Exception {
		this.mockMvc.perform(put("/tarea/orden").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errores.ids").exists());
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

	@Test
	@DisplayName("GET /tarea/configuracion expone el límite de caracteres del servidor")
	void exponeLaConfiguracion() throws Exception {
		this.mockMvc.perform(get("/tarea/configuracion"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maxCaracteresTexto").value(Tarea.MAX_CARACTERES_TEXTO));
	}

	/**
	 * Regresión: el manejador de errores llegó a tener un {@code @ExceptionHandler(Exception.class)}
	 * con prioridad máxima, lo que dejaba sin ejecutar al de Spring Boot y convertía este 405 en un
	 * 500. La solución fue que {@code ManejadorErroresGlobal} extienda
	 * {@code ResponseEntityExceptionHandler}: dentro de la misma clase, Spring elige el manejador más
	 * específico sin importar el orden.
	 */
	@Test
	@DisplayName("un método no soportado responde 405, no 500")
	void devuelve405ConMetodoNoSoportado() throws Exception {
		this.mockMvc.perform(post("/tarea/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	@DisplayName("un tipo de contenido que no es JSON responde 415, no 500")
	void devuelve415ConTipoDeContenidoNoJson() throws Exception {
		this.mockMvc.perform(post("/tarea").contentType(MediaType.TEXT_PLAIN).content("Repasar HTML"))
			.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	@DisplayName("GET /tarea/fondo devuelve el mapa de fondos")
	void devuelveLosFondos() throws Exception {
		given(this.servicio.consultarFondos()).willReturn(Map.of(1, Fondo.ONDAS, 4, Fondo.AURORA));

		this.mockMvc.perform(get("/tarea/fondo"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.1").value("ondas"))
			.andExpect(jsonPath("$.4").value("aurora"));
	}

	@Test
	@DisplayName("PUT /tarea/{id}/fondo asigna el fondo y devuelve el mapa actualizado")
	void cambiaElFondo() throws Exception {
		given(this.servicio.consultarFondos()).willReturn(Map.of(1, Fondo.PAPEL));

		this.mockMvc
			.perform(put("/tarea/1/fondo").contentType(MediaType.APPLICATION_JSON).content("{\"fondo\":\"papel\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.1").value("papel"));

		then(this.servicio).should().cambiarFondo(1, Fondo.PAPEL);
	}

	@Test
	@DisplayName("un fondo que no existe responde 400, no 500")
	void rechazaUnFondoDesconocido() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1/fondo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fondo\":\"purpurina\"}"))
			.andExpect(status().isBadRequest());

		then(this.servicio).should(never()).cambiarFondo(anyInt(), any());
	}

	@Test
	@DisplayName("cambiar el fondo de una tarea que no existe responde 404")
	void fondoDeTareaInexistenteDa404() throws Exception {
		willThrow(new TareaNoEncontradaException(99)).given(this.servicio).cambiarFondo(anyInt(), any());

		this.mockMvc
			.perform(put("/tarea/99/fondo").contentType(MediaType.APPLICATION_JSON).content("{\"fondo\":\"ondas\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("/tarea/fondo no se confunde con /tarea/{id}")
	void laRutaFondoTienePrioridadSobreLaPlantilla() throws Exception {
		given(this.servicio.consultarFondos()).willReturn(Map.of());

		this.mockMvc.perform(get("/tarea/fondo")).andExpect(status().isOk());

		then(this.servicio).should(never()).consultarPorId(anyInt());
	}

	@Test
	@DisplayName("PUT /tarea/{id} actualiza texto y estado y devuelve la tarea")
	void actualizaUnaTarea() throws Exception {
		given(this.servicio.actualizar(1, "Repasar CSS", true)).willReturn(new Tarea(1, "Repasar CSS", true));

		this.mockMvc
			.perform(put("/tarea/1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"Repasar CSS\",\"completada\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.texto").value("Repasar CSS"))
			.andExpect(jsonPath("$.completada").value(true));

		then(this.servicio).should().actualizar(1, "Repasar CSS", true);
	}

	/**
	 * El campo es {@code Boolean} y no {@code boolean} justamente para poder distinguir «me he
	 * olvidado del campo» de «lo mando en false». Con el primitivo, un cuerpo sin
	 * {@code completada} habría llegado al servicio como {@code false} y habría desmarcado la tarea
	 * sin que el cliente lo pidiera.
	 */
	@Test
	@DisplayName("PUT /tarea/{id} sin el campo completada responde 400 y no llega al servicio")
	void rechazaActualizarSinCompletada() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"Repasar CSS\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errores.completada").exists());

		then(this.servicio).should(never()).actualizar(anyInt(), anyString(), anyBoolean());
	}

	@Test
	@DisplayName("PUT /tarea/{id} con el texto vacío responde 400")
	void rechazaActualizarConTextoVacio() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"   \",\"completada\":false}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errores.texto").exists());

		then(this.servicio).should(never()).actualizar(anyInt(), anyString(), anyBoolean());
	}

	@Test
	@DisplayName("PUT /tarea/{id} de una tarea que no existe responde 404")
	void actualizarUnaTareaInexistenteResponde404() throws Exception {
		willThrow(new TareaNoEncontradaException(99)).given(this.servicio).actualizar(anyInt(), anyString(),
				anyBoolean());

		this.mockMvc
			.perform(put("/tarea/99").contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"Da igual\",\"completada\":false}"))
			.andExpect(status().isNotFound());
	}

	/**
	 * El cuerpo es JSON perfectamente válido; lo que no vale es el fondo. Antes se respondía «El
	 * cuerpo de la petición falta o no es JSON válido», que es falso y además no decía cuáles eran
	 * los buenos.
	 */
	@Test
	@DisplayName("un fondo desconocido explica cuáles son los válidos, sin decir que el JSON esté mal")
	void elFondoDesconocidoSeExplica() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1/fondo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fondo\":\"marmol\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("marmol")))
			.andExpect(jsonPath("$.detail").value(containsString("ondas")))
			.andExpect(jsonPath("$.detail").value(not(containsString("no es JSON válido"))));
	}

	/**
	 * El JSON declara los fondos en minúsculas y {@code @JsonValue} solo produce esa forma. Aceptar
	 * además la mayúscula dejaría la API con dos contratos: uno para leer y otro para escribir.
	 */
	@Test
	@DisplayName("un fondo en mayúsculas se rechaza, porque la API nunca lo devuelve así")
	void elFondoEnMayusculasSeRechaza() throws Exception {
		this.mockMvc
			.perform(put("/tarea/1/fondo").contentType(MediaType.APPLICATION_JSON).content("{\"fondo\":\"ONDAS\"}"))
			.andExpect(status().isBadRequest());

		then(this.servicio).should(never()).cambiarFondo(anyInt(), any());
	}

	@Test
	@DisplayName("un error inesperado responde 500 con formato ProblemDetail")
	void traduceExcepcionInesperadaA500() throws Exception {
		willThrow(new RuntimeException("fallo interno")).given(this.servicio).consultarTodas();

		this.mockMvc.perform(get("/tarea"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("Error inesperado"))
			.andExpect(jsonPath("$.detail").value("Ha ocurrido un error inesperado en el servidor."));
	}

}

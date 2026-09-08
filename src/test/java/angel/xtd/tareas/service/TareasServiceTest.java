package angel.xtd.tareas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import angel.xtd.tareas.almacen.AlmacenTareas;
import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.TareaNoEncontradaException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pruebas del servicio contra un almacén real apuntando a un archivo temporal.
 *
 * <p>No se simula el almacén: la parte interesante de esta app es justamente que el orden y los ids
 * sobrevivan a la ida y vuelta al disco, y eso un doble de prueba no lo comprobaría.
 */
class TareasServiceTest {

	@TempDir
	Path directorio;

	private Path archivo;

	private TareasService servicio;

	@BeforeEach
	void preparar() {
		this.archivo = this.directorio.resolve("tareas.json");
		this.servicio = new TareasService(nuevoAlmacen());
	}

	private AlmacenTareas nuevoAlmacen() {
		ObjectMapper mapper = JsonMapper.builder().build();
		AlmacenTareas almacen = new AlmacenTareas(mapper, new PropiedadesAlmacen(this.archivo.toString()));
		almacen.cargarDesdeArchivo();
		return almacen;
	}

	@Test
	@DisplayName("arranca vacío si el archivo todavía no existe")
	void arrancaVacio() {
		assertThat(this.servicio.consultarTodas()).isEmpty();
	}

	@Test
	@DisplayName("las tareas nuevas se añaden al final y reciben ids consecutivos")
	void creaAlFinalConIdsConsecutivos() {
		Tarea primera = this.servicio.crear("Repasar HTML");
		Tarea segunda = this.servicio.crear("Repasar CSS");

		assertThat(primera.id()).isEqualTo(Tarea.PRIMER_ID);
		assertThat(segunda.id()).isEqualTo(Tarea.PRIMER_ID + 1);
		assertThat(this.servicio.consultarTodas()).extracting(Tarea::texto).containsExactly("Repasar HTML",
				"Repasar CSS");
	}

	@Test
	@DisplayName("recorta los espacios sobrantes del texto")
	void recortaEspacios() {
		Tarea creada = this.servicio.crear("   Repasar JS   ");

		assertThat(creada.texto()).isEqualTo("Repasar JS");
	}

	@Test
	@DisplayName("una tarea nueva nunca reutiliza el id de una borrada (regresión del esqueleto)")
	void noReutilizaIdsTrasBorrar() {
		this.servicio.crear("Uno");
		Tarea dos = this.servicio.crear("Dos");
		this.servicio.crear("Tres");

		this.servicio.eliminar(dos.id());
		Tarea cuarta = this.servicio.crear("Cuatro");

		// Con `nuevoId = mapa.size()` aquí saldría un id ya ocupado y se machacaría otra tarea.
		List<Integer> ids = this.servicio.consultarTodas().stream().map(Tarea::id).toList();
		assertThat(cuarta.id()).isEqualTo(4);
		assertThat(ids).doesNotHaveDuplicates().containsExactly(1, 3, 4);
	}

	@Test
	@DisplayName("actualizar conserva el id y la posición")
	void actualizaConservandoPosicion() {
		this.servicio.crear("Uno");
		Tarea dos = this.servicio.crear("Dos");
		this.servicio.crear("Tres");

		Tarea actualizada = this.servicio.actualizar(dos.id(), "Dos editada", true);

		assertThat(actualizada.id()).isEqualTo(dos.id());
		assertThat(this.servicio.consultarTodas()).extracting(Tarea::texto).containsExactly("Uno", "Dos editada",
				"Tres");
	}

	@Test
	@DisplayName("marcar completada conserva el id y el texto")
	void marcarCompletadaConservaElResto() {
		Tarea creada = this.servicio.crear("Uno");

		Tarea marcada = this.servicio.actualizar(creada.id(), creada.texto(), true);

		assertThat(marcada.completada()).isTrue();
		assertThat(marcada.texto()).isEqualTo("Uno");
		assertThat(marcada.id()).isEqualTo(creada.id());
	}

	@Test
	@DisplayName("operar sobre una tarea inexistente lanza TareaNoEncontradaException")
	void fallaConTareaInexistente() {
		assertThatExceptionOfType(TareaNoEncontradaException.class).isThrownBy(() -> this.servicio.eliminar(42));
		assertThatExceptionOfType(TareaNoEncontradaException.class).isThrownBy(() -> this.servicio.consultarPorId(42));
		assertThatExceptionOfType(TareaNoEncontradaException.class)
			.isThrownBy(() -> this.servicio.actualizar(42, "x", false));
	}

	@Test
	@DisplayName("el orden y los ids sobreviven a un reinicio")
	void persisteOrdenEIdsEntreReinicios() {
		Tarea uno = this.servicio.crear("Uno");
		Tarea dos = this.servicio.crear("Dos");
		Tarea tres = this.servicio.crear("Tres");
		this.servicio.eliminar(dos.id());

		TareasService servicioReiniciado = new TareasService(nuevoAlmacen());

		assertThat(servicioReiniciado.consultarTodas()).extracting(Tarea::id)
			.containsExactly(uno.id(), tres.id());
	}

	@Test
	@DisplayName("el JSON guardado contiene exactamente los tres campos y ninguno de orden")
	void elJsonGuardadoSoloTieneTresCampos() throws IOException {
		this.servicio.crear("Repasar HTML");

		String json = Files.readString(this.archivo, StandardCharsets.UTF_8);

		assertThat(json).contains("\"id\"").contains("\"texto\"").contains("\"completada\"");
		assertThat(json).doesNotContain("orden").doesNotContain("posicion");
	}

	@Test
	@DisplayName("cada modificación se guarda sola, sin llamar a nada para persistir")
	void guardaEnCadaModificacion() throws IOException {
		Tarea creada = this.servicio.crear("Uno");
		assertThat(Files.readString(this.archivo)).contains("Uno");

		this.servicio.actualizar(creada.id(), "Uno editada", false);
		assertThat(Files.readString(this.archivo)).contains("Uno editada");

		this.servicio.eliminar(creada.id());
		assertThat(Files.readString(this.archivo)).doesNotContain("Uno editada");
	}

}

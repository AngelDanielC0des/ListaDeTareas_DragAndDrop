package angel.xtd.tareas.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import angel.xtd.tareas.dto.Tarea;

/**
 * Pruebas del servicio, que en esta versión es también quien guarda la lista.
 *
 * <p>No hacen falta archivos temporales ni dobles de prueba: basta con instanciarlo, porque todo el
 * estado vive dentro.
 */
class TareasServiceTest {

	private TareasService servicio;

	@BeforeEach
	void preparar() {
		this.servicio = new TareasService();
	}

	@Test
	@DisplayName("la lista empieza vacía al arrancar")
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
		assertThat(this.servicio.consultarTodas()).extracting(Tarea::texto)
			.containsExactly("Repasar HTML", "Repasar CSS");
	}

	@Test
	@DisplayName("una tarea recién creada no está completada")
	void naceSinCompletar() {
		assertThat(this.servicio.crear("Uno").completada()).isFalse();
	}

	@Test
	@DisplayName("recorta los espacios sobrantes del texto")
	void recortaEspacios() {
		assertThat(this.servicio.crear("   Repasar JS   ").texto()).isEqualTo("Repasar JS");
	}

	@Test
	@DisplayName("una tarea nueva nunca reutiliza el id de una borrada (regresión del esqueleto)")
	void noReutilizaIdsTrasBorrar() {
		this.servicio.crear("Uno");
		Tarea dos = this.servicio.crear("Dos");
		this.servicio.crear("Tres");

		this.servicio.eliminar(dos.id());
		Tarea cuarta = this.servicio.crear("Cuatro");

		// Con `nuevoId = lista.size()` aquí saldría un id ya ocupado y se machacaría otra tarea.
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

		Tarea actualizada = this.servicio.actualizar(dos.id(), "Dos editada", true).orElseThrow();

		assertThat(actualizada.id()).isEqualTo(dos.id());
		assertThat(actualizada.completada()).isTrue();
		assertThat(this.servicio.consultarTodas()).extracting(Tarea::texto)
			.containsExactly("Uno", "Dos editada", "Tres");
	}

	@Test
	@DisplayName("eliminar quita la tarea y respeta el orden de las demás")
	void eliminaConservandoElOrden() {
		this.servicio.crear("Uno");
		Tarea dos = this.servicio.crear("Dos");
		this.servicio.crear("Tres");

		assertThat(this.servicio.eliminar(dos.id())).isTrue();

		assertThat(this.servicio.consultarTodas()).extracting(Tarea::texto).containsExactly("Uno", "Tres");
	}

	@Test
	@DisplayName("operar sobre una tarea inexistente avisa en vez de fallar")
	void avisaSiLaTareaNoExiste() {
		assertThat(this.servicio.buscarPorId(42)).isEmpty();
		assertThat(this.servicio.actualizar(42, "x", false)).isEmpty();
		assertThat(this.servicio.eliminar(42)).isFalse();
	}

	@Test
	@DisplayName("consultarTodas devuelve una copia que no permite tocar el estado interno")
	void devuelveUnaCopiaInmutable() {
		this.servicio.crear("Uno");

		List<Tarea> copia = this.servicio.consultarTodas();

		assertThat(copia).isUnmodifiable();
		assertThat(this.servicio.consultarTodas()).hasSize(1);
	}

	@Test
	@DisplayName("cada servicio nuevo empieza de cero: no hay nada guardado entre arranques")
	void noPersisteEntreArranques() {
		this.servicio.crear("Uno");
		this.servicio.crear("Dos");

		// Equivale a reiniciar la aplicación: el estado vivía solo en la instancia anterior.
		TareasService servicioReiniciado = new TareasService();

		assertThat(servicioReiniciado.consultarTodas()).isEmpty();
		assertThat(servicioReiniciado.crear("Primera tras reiniciar").id()).isEqualTo(Tarea.PRIMER_ID);
	}

}

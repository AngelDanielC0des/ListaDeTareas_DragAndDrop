package angel.xtd.tareas.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Grupo;
import angel.xtd.tareas.error.AlmacenamientoException;
import angel.xtd.tareas.error.GrupoNoEncontradoException;
import angel.xtd.tareas.error.OrdenInvalidoException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pruebas del almacén de grupos.
 *
 * <p>Lo que más importa comprobar aquí no es crear y borrar, que se ve leyendo, sino las dos cosas
 * que sostienen la coherencia: que borrar un grupo <b>no</b> se lleve por delante sus tareas, y que
 * no queden asignaciones apuntando a algo que ya no existe.
 */
class AlmacenGruposTest {

	@TempDir
	Path directorio;

	private Path archivo;

	@BeforeEach
	void preparar() {
		this.archivo = this.directorio.resolve("grupos.json");
	}

	private AlmacenGrupos nuevoAlmacen() {
		AlmacenGrupos almacen = new AlmacenGrupos(JsonMapper.builder().build(),
				new PropiedadesAlmacen(this.directorio.resolve("tareas.json").toString(),
						this.directorio.resolve("fondos.json").toString(), this.archivo.toString()));
		almacen.cargarDesdeArchivo();
		return almacen;
	}

	@Test
	@DisplayName("al principio no hay ningún grupo")
	void empiezaVacio() {
		assertThat(nuevoAlmacen().consultarTodo().grupos()).isEmpty();
	}

	@Test
	@DisplayName("los grupos nuevos se añaden al final y reciben ids consecutivos")
	void creaConIdsConsecutivos() {
		AlmacenGrupos almacen = nuevoAlmacen();

		Grupo manana = almacen.crear("Mañana");
		Grupo casa = almacen.crear("Casa");

		assertThat(manana.id()).isEqualTo(1);
		assertThat(casa.id()).isEqualTo(2);
		assertThat(almacen.consultarTodo().grupos()).extracting(Grupo::nombre).containsExactly("Mañana", "Casa");
	}

	/** Un id que se reutilizara haría que las tareas del grupo viejo aparecieran en el nuevo. */
	@Test
	@DisplayName("un grupo nuevo nunca reutiliza el id de uno borrado")
	void noReutilizaIds() {
		AlmacenGrupos almacen = nuevoAlmacen();
		almacen.crear("Mañana");
		Grupo casa = almacen.crear("Casa");

		almacen.eliminar(casa.id());
		Grupo tercero = almacen.crear("Trabajo");

		assertThat(tercero.id()).isEqualTo(3);
	}

	@Test
	@DisplayName("renombrar conserva el id y la posición")
	void renombrarConservaIdYPosicion() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");
		almacen.crear("Casa");

		almacen.renombrar(manana.id(), "Hoy");

		assertThat(almacen.consultarTodo().grupos()).extracting(Grupo::id).containsExactly(1, 2);
		assertThat(almacen.consultarTodo().grupos().get(0).nombre()).isEqualTo("Hoy");
	}

	/**
	 * Es la decisión menos destructiva y la que espera cualquiera: un grupo es una forma de ordenar
	 * lo que hay, no un contenedor del que las tareas dependan para existir.
	 */
	@Test
	@DisplayName("borrar un grupo deja sus tareas sueltas, no las borra")
	void borrarUnGrupoDejaSusTareasSueltas() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");
		almacen.asignar(7, manana.id());
		almacen.asignar(8, manana.id());

		almacen.eliminar(manana.id());

		assertThat(almacen.consultarTodo().grupos()).isEmpty();
		assertThat(almacen.consultarTodo().asignaciones())
			.as("las asignaciones al grupo borrado desaparecen, pero las tareas no son cosa de este almacén")
			.isEmpty();
	}

	@Test
	@DisplayName("asignar mete la tarea en el grupo y null la deja suelta")
	void asignaYDesasigna() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");

		almacen.asignar(7, manana.id());
		assertThat(almacen.consultarTodo().asignaciones()).containsExactly(Map.entry(7, 1));

		almacen.asignar(7, null);
		assertThat(almacen.consultarTodo().asignaciones()).isEmpty();
	}

	@Test
	@DisplayName("asignar a un grupo que no existe responde que no existe")
	void asignarAGrupoInexistente() {
		AlmacenGrupos almacen = nuevoAlmacen();

		assertThatExceptionOfType(GrupoNoEncontradoException.class).isThrownBy(() -> almacen.asignar(7, 99));

		assertThat(almacen.consultarTodo().asignaciones()).as("y no deja rastro").isEmpty();
	}

	@Test
	@DisplayName("olvidar una tarea la saca de su grupo")
	void olvidarUnaTarea() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");
		almacen.asignar(7, manana.id());

		almacen.olvidarTarea(7);

		assertThat(almacen.consultarTodo().asignaciones()).isEmpty();
	}

	@Test
	@DisplayName("olvidar una tarea que no estaba en ningún grupo no reescribe el archivo")
	void olvidarUnaTareaSinGrupoNoReescribe() throws Exception {
		AlmacenGrupos almacen = nuevoAlmacen();
		almacen.crear("Mañana");
		Files.setLastModifiedTime(this.archivo, java.nio.file.attribute.FileTime.fromMillis(0));

		almacen.olvidarTarea(99);

		assertThat(Files.getLastModifiedTime(this.archivo).toMillis()).isZero();
	}

	@Test
	@DisplayName("conservarSolo descarta las asignaciones de tareas que ya no existen")
	void conservarSoloDescartaHuerfanas() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");
		almacen.asignar(7, manana.id());
		almacen.asignar(8, manana.id());

		almacen.conservarSoloTareas(List.of(7));

		assertThat(almacen.consultarTodo().asignaciones()).containsExactly(Map.entry(7, 1));
	}

	@Test
	@DisplayName("reordenar cambia las posiciones pero no los ids")
	void reordenarNoCambiaLosIds() {
		AlmacenGrupos almacen = nuevoAlmacen();
		almacen.crear("Mañana");
		almacen.crear("Casa");
		almacen.crear("Trabajo");

		almacen.reordenar(List.of(3, 1, 2));

		assertThat(almacen.consultarTodo().grupos()).extracting(Grupo::id).containsExactly(3, 1, 2);
		assertThat(almacen.consultarTodo().grupos()).extracting(Grupo::nombre)
			.containsExactly("Trabajo", "Mañana", "Casa");
	}

	/** Sin esto, un cliente con la lista desactualizada haría desaparecer grupos al reordenar. */
	@Test
	@DisplayName("rechaza un orden que no es una permutación exacta")
	void rechazaOrdenIncompleto() {
		AlmacenGrupos almacen = nuevoAlmacen();
		almacen.crear("Mañana");
		almacen.crear("Casa");

		assertThatExceptionOfType(OrdenInvalidoException.class).isThrownBy(() -> almacen.reordenar(List.of(1)));

		assertThat(almacen.consultarTodo().grupos()).as("y la lista queda intacta").hasSize(2);
	}

	@Test
	@DisplayName("los grupos y sus asignaciones sobreviven a un reinicio")
	void sobrevivenAlReinicio() {
		AlmacenGrupos almacen = nuevoAlmacen();
		Grupo manana = almacen.crear("Mañana");
		almacen.asignar(7, manana.id());

		AlmacenGrupos reiniciado = nuevoAlmacen();

		assertThat(reiniciado.consultarTodo().grupos()).extracting(Grupo::nombre).containsExactly("Mañana");
		assertThat(reiniciado.consultarTodo().asignaciones()).containsExactly(Map.entry(7, 1));
	}

	/**
	 * Una asignación a un grupo inexistente no se puede pintar y no hay forma de arreglarla desde la
	 * interfaz, así que se descarta al cargar en vez de dejarla envenenando el mapa.
	 */
	@Test
	@DisplayName("al cargar descarta las asignaciones que apuntan a un grupo que no existe")
	void descartaAsignacionesSinGrupo() throws Exception {
		Files.writeString(this.archivo, """
				{"grupos":[{"id":1,"nombre":"Mañana"}],"asignaciones":{"7":1,"8":99}}
				""", StandardCharsets.UTF_8);

		AlmacenGrupos almacen = nuevoAlmacen();

		assertThat(almacen.consultarTodo().asignaciones()).containsExactly(Map.entry(7, 1));
	}

	@Test
	@DisplayName("un archivo con grupos repetidos se rechaza sin dejar el almacén a medio cargar")
	void rechazaIdsRepetidos() throws Exception {
		Files.writeString(this.archivo, """
				{"grupos":[{"id":1,"nombre":"Mañana"},{"id":1,"nombre":"Otra"}],"asignaciones":{}}
				""", StandardCharsets.UTF_8);

		AlmacenGrupos almacen = new AlmacenGrupos(JsonMapper.builder().build(),
				new PropiedadesAlmacen(this.directorio.resolve("tareas.json").toString(),
						this.directorio.resolve("fondos.json").toString(), this.archivo.toString()));

		assertThatExceptionOfType(AlmacenamientoException.class).isThrownBy(almacen::cargarDesdeArchivo)
			.withMessageContaining("más de una vez");
		assertThat(almacen.consultarTodo().grupos()).isEmpty();
	}

	@Test
	@DisplayName("consultarTodo devuelve copias que no permiten tocar el estado interno")
	void devuelveCopiasInmutables() {
		AlmacenGrupos almacen = nuevoAlmacen();
		almacen.crear("Mañana");

		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> almacen.consultarTodo().grupos().add(new Grupo(99, "Colada")));
		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> almacen.consultarTodo().asignaciones().put(1, 1));
	}

}

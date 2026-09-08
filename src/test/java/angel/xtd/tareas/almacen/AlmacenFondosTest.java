package angel.xtd.tareas.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.error.AlmacenamientoException;
import tools.jackson.databind.json.JsonMapper;

/** Pruebas del almacén de fondos, que guarda la asociación de cada tarea con su imagen. */
class AlmacenFondosTest {

	@TempDir
	Path directorio;

	private Path archivo;

	@BeforeEach
	void preparar() {
		this.archivo = this.directorio.resolve("fondos.json");
	}

	private AlmacenFondos nuevoAlmacen() {
		AlmacenFondos almacen = new AlmacenFondos(JsonMapper.builder().build(),
				new PropiedadesAlmacen(this.directorio.resolve("tareas.json").toString(), this.archivo.toString()));
		almacen.cargarDesdeArchivo();
		return almacen;
	}

	@Test
	@DisplayName("al principio ninguna tarea tiene fondo")
	void empiezaVacio() {
		assertThat(nuevoAlmacen().consultarTodos()).isEmpty();
	}

	@Test
	@DisplayName("asignar un fondo lo guarda y lo devuelve")
	void asignaYDevuelve() {
		AlmacenFondos almacen = nuevoAlmacen();

		almacen.asignar(1, Fondo.ONDAS);

		assertThat(almacen.consultarTodos()).containsExactly(Map.entry(1, Fondo.ONDAS));
	}

	/**
	 * «Ninguno» no se guarda como un valor: se guarda como ausencia de entrada. Si no, el archivo
	 * acabaría lleno de tareas marcadas explícitamente como «sin fondo», que es lo mismo que nada.
	 */
	@Test
	@DisplayName("elegir «ninguno» borra la entrada en vez de guardarla")
	void ningunoQuitaLaEntrada() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.PAPEL);

		almacen.asignar(1, Fondo.NINGUNO);

		assertThat(almacen.consultarTodos()).isEmpty();
	}

	@Test
	@DisplayName("olvidar quita el fondo de una tarea borrada")
	void olvidaAlBorrar() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.ONDAS);
		almacen.asignar(2, Fondo.AURORA);

		almacen.olvidar(1);

		assertThat(almacen.consultarTodos()).containsOnlyKeys(2);
	}

	@Test
	@DisplayName("olvidar una tarea sin fondo no hace nada ni reescribe el archivo")
	void olvidarSinFondoNoHaceNada() {
		AlmacenFondos almacen = nuevoAlmacen();

		almacen.olvidar(99);

		assertThat(Files.exists(this.archivo)).as("no debe crear el archivo por una operación vacía").isFalse();
	}

	@Test
	@DisplayName("conservarSolo descarta los fondos de tareas que ya no existen")
	void conservaSoloLasQueExisten() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.ONDAS);
		almacen.asignar(2, Fondo.PUNTOS);
		almacen.asignar(3, Fondo.LINEAS);

		almacen.conservarSolo(List.of(1, 3));

		assertThat(almacen.consultarTodos()).containsOnlyKeys(1, 3);
	}

	@Test
	@DisplayName("los fondos sobreviven a un reinicio")
	void persisteEntreReinicios() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.AURORA);
		almacen.asignar(4, Fondo.PAPEL);

		assertThat(nuevoAlmacen().consultarTodos()).containsOnlyKeys(1, 4)
			.containsEntry(1, Fondo.AURORA)
			.containsEntry(4, Fondo.PAPEL);
	}

	@Test
	@DisplayName("el JSON guarda los ids ordenados, que es para lo que se usa un TreeMap")
	void guardaLosIdsOrdenados() throws Exception {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(30, Fondo.ONDAS);
		almacen.asignar(2, Fondo.PUNTOS);
		almacen.asignar(11, Fondo.PAPEL);

		String json = Files.readString(this.archivo, StandardCharsets.UTF_8);

		assertThat(json.indexOf("\"2\"")).isLessThan(json.indexOf("\"11\""));
		assertThat(json.indexOf("\"11\"")).isLessThan(json.indexOf("\"30\""));
	}

	@Test
	@DisplayName("el JSON usa el nombre del fondo en minúsculas")
	void guardaElNombreEnMinusculas() throws Exception {
		nuevoAlmacen().asignar(1, Fondo.AURORA);

		assertThat(Files.readString(this.archivo, StandardCharsets.UTF_8)).contains("\"aurora\"");
	}

	@Test
	@DisplayName("si la escritura falla, el mapa en memoria vuelve a como estaba")
	void deshaceSiLaEscrituraFalla() throws Exception {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.ONDAS);

		// Se sustituye el archivo por un directorio no vacío para que el siguiente guardado falle.
		Files.delete(this.archivo);
		Files.createDirectory(this.archivo);
		Files.createDirectory(this.archivo.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class).isThrownBy(() -> almacen.asignar(2, Fondo.PAPEL));

		assertThat(almacen.consultarTodos()).containsOnlyKeys(1);
	}

	@Test
	@DisplayName("consultarTodos devuelve una copia que no permite tocar el estado interno")
	void devuelveUnaCopiaInmutable() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.ONDAS);

		assertThat(almacen.consultarTodos()).isUnmodifiable();
	}

	/**
	 * {@code olvidar} ya evitaba la escritura inútil y {@code asignar} no. Elegir dos veces el mismo
	 * fondo reescribía el archivo entero, y el selector invita a eso porque muestra el fondo actual
	 * como una opción más que se puede volver a pulsar.
	 */
	@Test
	@DisplayName("asignar el mismo fondo dos veces no reescribe el archivo")
	void asignarLoMismoNoReescribe() throws Exception {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(1, Fondo.ONDAS);

		// Se marca el archivo con una fecha reconocible: si la segunda asignación lo reescribiera, la
		// fecha cambiaría. Es más fiable que comparar marcas de tiempo reales, que en Windows tienen
		// poca resolución y podrían coincidir.
		Files.setLastModifiedTime(this.archivo, FileTime.fromMillis(0));

		almacen.asignar(1, Fondo.ONDAS);

		assertThat(Files.getLastModifiedTime(this.archivo).toMillis())
			.as("no debería haberse tocado el archivo")
			.isZero();
		assertThat(almacen.consultarTodos()).as("y el mapa debe seguir bien")
			.containsExactly(Map.entry(1, Fondo.ONDAS));
	}

	/**
	 * {@code AlmacenTareas} limpia su lista antes de cargar y este hacía {@code putAll} a secas, así
	 * que una recarga conservaba entradas que el archivo ya no tenía.
	 */
	@Test
	@DisplayName("recargar descarta los fondos que ya no están en el archivo")
	void recargarDescartaLoViejo() throws Exception {
		Files.writeString(this.archivo, "{\"1\":\"ondas\",\"2\":\"puntos\"}", StandardCharsets.UTF_8);
		AlmacenFondos almacen = nuevoAlmacen();
		assertThat(almacen.consultarTodos()).hasSize(2);

		Files.writeString(this.archivo, "{\"3\":\"papel\"}", StandardCharsets.UTF_8);
		almacen.cargarDesdeArchivo();

		assertThat(almacen.consultarTodos()).containsExactly(Map.entry(3, Fondo.PAPEL));
	}

	/**
	 * El {@code TreeMap} se eligió para que las claves salieran ordenadas. {@code Map.copyOf} devuelve
	 * un mapa sin orden garantizado, así que esa ventaja se perdía justo al salir de la clase.
	 */
	@Test
	@DisplayName("el mapa que se devuelve conserva el orden por id")
	void elMapaDevueltoVaOrdenado() {
		AlmacenFondos almacen = nuevoAlmacen();
		almacen.asignar(30, Fondo.ONDAS);
		almacen.asignar(4, Fondo.PAPEL);
		almacen.asignar(17, Fondo.PUNTOS);

		assertThat(almacen.consultarTodos().keySet()).containsExactly(4, 17, 30);
	}

}

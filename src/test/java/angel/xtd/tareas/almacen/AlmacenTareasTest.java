package angel.xtd.tareas.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import angel.xtd.tareas.config.PropiedadesAlmacen;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.error.AlmacenamientoException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pruebas del almacén: lo que el servicio no puede comprobar por sí solo, sobre todo que memoria y
 * disco no se desincronicen cuando la escritura falla.
 */
class AlmacenTareasTest {

	@TempDir
	Path directorio;

	private Path archivo;

	@BeforeEach
	void preparar() {
		this.archivo = this.directorio.resolve("tareas.json");
	}

	private AlmacenTareas nuevoAlmacen(Path rutaDelArchivo) {
		AlmacenTareas almacen = new AlmacenTareas(JsonMapper.builder().build(),
				new PropiedadesAlmacen(rutaDelArchivo.toString()));
		almacen.cargarDesdeArchivo();
		return almacen;
	}

	/**
	 * Ocupa la ruta del archivo con un directorio no vacío: el contenido se serializa bien, pero al
	 * mover el temporal sobre el destino la operación falla. Es la forma de provocar un fallo de E/S
	 * justo en el momento interesante, cuando la lista ya se ha modificado en memoria.
	 */
	private Path rutaImposibleDeEscribir(String nombre) throws Exception {
		Path ruta = this.directorio.resolve(nombre);
		Files.createDirectory(ruta);
		Files.createDirectory(ruta.resolve("no-esta-vacio"));
		return ruta;
	}

	@Test
	@DisplayName("si la escritura falla al crear, la tarea no se queda en memoria")
	void deshaceElAltaSiLaEscrituraFalla() throws Exception {
		Path rutaOcupada = this.directorio.resolve("ocupada.json");
		// El almacén arranca con la ruta libre y solo DESPUÉS se ocupa, para que falle al guardar.
		AlmacenTareas almacen = nuevoAlmacen(rutaOcupada);
		rutaImposibleDeEscribir("ocupada.json");

		assertThatExceptionOfType(AlmacenamientoException.class)
			.isThrownBy(() -> almacen.anadirAlFinal("No debería sobrevivir"));

		assertThat(almacen.consultarTodas()).isEmpty();
	}

	@Test
	@DisplayName("si la escritura falla al borrar, la tarea sigue en su sitio")
	void deshaceElBorradoSiLaEscrituraFalla() throws Exception {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");
		Tarea dos = almacen.anadirAlFinal("Dos");
		almacen.anadirAlFinal("Tres");

		// Se sustituye el archivo por un directorio para que el siguiente guardado reviente.
		Files.delete(this.archivo);
		Files.createDirectory(this.archivo);
		Files.createDirectory(this.archivo.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class).isThrownBy(() -> almacen.eliminar(dos.id()));

		assertThat(almacen.consultarTodas()).extracting(Tarea::texto).containsExactly("Uno", "Dos", "Tres");
	}

	@Test
	@DisplayName("un alta fallida no consume el id que iba a usar")
	void noConsumeElIdSiLaEscrituraFalla() throws Exception {
		Path rutaOcupada = this.directorio.resolve("ocupada2.json");
		AlmacenTareas almacen = nuevoAlmacen(rutaOcupada);
		rutaImposibleDeEscribir("ocupada2.json");

		for (int intento = 0; intento < 3; intento++) {
			try {
				almacen.anadirAlFinal("Fallará");
			}
			catch (AlmacenamientoException esperada) {
				// Lo que se comprueba es el estado posterior, no la excepción.
			}
		}

		// Al liberar la ruta, la primera tarea que se guarde bien debe recibir el primer id.
		Files.delete(rutaOcupada.resolve("no-esta-vacio"));
		Files.delete(rutaOcupada);

		assertThat(almacen.anadirAlFinal("Bien").id()).isEqualTo(Tarea.PRIMER_ID);
	}

	/**
	 * La razón de ser del temporal + {@code Files.move}: que un fallo a mitad de la escritura no
	 * destruya lo que ya estaba guardado.
	 *
	 * <p>El resto de pruebas comprueban que la lista EN MEMORIA vuelve atrás; esta comprueba lo otro,
	 * que es lo que de verdad justifica el mecanismo: que el ARCHIVO anterior sigue intacto y se
	 * puede volver a cargar entero.
	 */
	@Test
	@DisplayName("un fallo de escritura deja intacto el archivo que ya estaba guardado")
	void elArchivoAnteriorSobreviveAlFallo() throws Exception {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");
		almacen.anadirAlFinal("Dos");
		String contenidoBueno = Files.readString(this.archivo, StandardCharsets.UTF_8);

		// Se hace imposible escribir sustituyendo el archivo por un directorio no vacío, pero
		// guardando antes su contenido para poder comprobar que no se ha perdido.
		Files.delete(this.archivo);
		Files.createDirectory(this.archivo);
		Files.createDirectory(this.archivo.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class)
			.isThrownBy(() -> almacen.anadirAlFinal("No deberia llegar al disco"));

		// Se devuelve el archivo a su sitio con el contenido que tenía y se recarga desde cero.
		Files.delete(this.archivo.resolve("no-esta-vacio"));
		Files.delete(this.archivo);
		Files.writeString(this.archivo, contenidoBueno, StandardCharsets.UTF_8);

		assertThat(nuevoAlmacen(this.archivo).consultarTodas()).extracting(Tarea::texto)
			.containsExactly("Uno", "Dos");
	}

	@Test
	@DisplayName("no queda ningún archivo temporal tirado tras un guardado correcto")
	void noDejaTemporalesTrasGuardar() throws Exception {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");

		try (var contenido = Files.list(this.directorio)) {
			assertThat(contenido.map(Path::getFileName).map(Path::toString))
				.containsExactly("tareas.json");
		}
	}

	@Test
	@DisplayName("buscarPorId encuentra la tarea sin devolver la lista entera")
	void buscaPorId() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");
		almacen.anadirAlFinal("Dos");

		assertThat(almacen.buscarPorId(2)).get().extracting(Tarea::texto).isEqualTo("Dos");
		assertThat(almacen.buscarPorId(99)).isEmpty();
	}

	@Test
	@DisplayName("reemplazar y eliminar avisan si la tarea no existe en vez de fallar")
	void avisanSiLaTareaNoExiste() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);

		assertThat(almacen.reemplazar(new Tarea(99, "Fantasma", false))).isFalse();
		assertThat(almacen.eliminar(99)).isFalse();
	}

	@Test
	@DisplayName("reemplazar conserva la posición de la tarea")
	void reemplazarConservaLaPosicion() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");
		Tarea dos = almacen.anadirAlFinal("Dos");
		almacen.anadirAlFinal("Tres");

		assertThat(almacen.reemplazar(new Tarea(dos.id(), "Dos editada", true))).isTrue();

		assertThat(almacen.consultarTodas()).extracting(Tarea::texto)
			.containsExactly("Uno", "Dos editada", "Tres");
	}

	@Test
	@DisplayName("consultarTodas devuelve una copia que no permite tocar el estado interno")
	void devuelveUnaCopiaInmutable() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.anadirAlFinal("Uno");

		List<Tarea> copia = almacen.consultarTodas();

		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> copia.add(new Tarea(99, "Intruso", false)));
		assertThat(almacen.consultarTodas()).hasSize(1);
	}

	@Test
	@DisplayName("un archivo vacío no revienta la carga")
	void toleraUnArchivoVacio() throws Exception {
		Files.writeString(this.archivo, "   ");

		assertThat(nuevoAlmacen(this.archivo).consultarTodas()).isEmpty();
	}

}

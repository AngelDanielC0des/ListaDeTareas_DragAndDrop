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
				new PropiedadesAlmacen(rutaDelArchivo.toString(), this.directorio.resolve("fondos.json").toString()));
		almacen.cargarDesdeArchivo();
		return almacen;
	}

	@Test
	@DisplayName("si la escritura falla, la lista en memoria vuelve a como estaba")
	void deshaceElCambioSiLaEscrituraFalla() throws Exception {
		// Se ocupa la ruta del archivo con un DIRECTORIO: el contenido se serializa bien, pero al
		// mover el temporal sobre el destino la operación falla. Es la manera de provocar un fallo
		// de E/S justo en el momento interesante, después de haber mutado ya la lista.
		Path rutaOcupada = this.directorio.resolve("ocupada.json");
		AlmacenTareas almacen = nuevoAlmacen(rutaOcupada);

		// El almacen arranca con la ruta libre y solo DESPUES se ocupa con un directorio no vacio:
		// asi el fallo ocurre al mover el temporal sobre el destino, que es el momento interesante,
		// y no antes al cargar.
		Files.createDirectory(rutaOcupada);
		Files.createDirectory(rutaOcupada.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class).isThrownBy(() -> almacen.modificar((tareas, reservarId) -> {
			tareas.add(new Tarea(reservarId.getAsInt(), "No debería sobrevivir", false));
			return null;
		}));

		assertThat(almacen.consultarTodas()).isEmpty();
	}

	@Test
	@DisplayName("un fallo de escritura tampoco consume el id que se había reservado")
	void deshaceLaReservaDeIdSiLaEscrituraFalla() throws Exception {
		Path rutaOcupada = this.directorio.resolve("ocupada2.json");
		AlmacenTareas almacen = nuevoAlmacen(rutaOcupada);

		Files.createDirectory(rutaOcupada);
		Files.createDirectory(rutaOcupada.resolve("no-esta-vacio"));

		for (int intento = 0; intento < 3; intento++) {
			try {
				almacen.modificar((tareas, reservarId) -> {
					tareas.add(new Tarea(reservarId.getAsInt(), "Fallará", false));
					return null;
				});
			}
			catch (AlmacenamientoException esperada) {
				// Lo que se comprueba es el estado posterior, no la excepción.
			}
		}

		// Tres intentos fallidos no deben haber gastado los ids 1, 2 y 3: al liberar la ruta, la
		// primera tarea que se guarde bien tiene que seguir recibiendo el primer id.
		Files.delete(rutaOcupada.resolve("no-esta-vacio"));
		Files.delete(rutaOcupada);

		Tarea creada = almacen.modificar((tareas, reservarId) -> {
			Tarea nueva = new Tarea(reservarId.getAsInt(), "Bien", false);
			tareas.add(nueva);
			return nueva;
		});
		assertThat(creada.id()).isEqualTo(Tarea.PRIMER_ID);
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
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Uno", false)));
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Dos", false)));
		String contenidoBueno = Files.readString(this.archivo, StandardCharsets.UTF_8);

		// Se hace imposible escribir sustituyendo el archivo por un directorio no vacío, pero
		// guardando antes su contenido para poder comprobar que no se ha perdido.
		Files.delete(this.archivo);
		Files.createDirectory(this.archivo);
		Files.createDirectory(this.archivo.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class)
			.isThrownBy(() -> almacen.modificar((tareas, reservarId) -> tareas
				.add(new Tarea(reservarId.getAsInt(), "No deberia llegar al disco", false))));

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
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Uno", false)));

		try (var contenido = Files.list(this.directorio)) {
			assertThat(contenido.map(Path::getFileName).map(Path::toString)).containsExactly("tareas.json");
		}
	}

	@Test
	@DisplayName("buscarPorId encuentra la tarea sin devolver la lista entera")
	void buscaPorIdSinCopiarLaLista() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Uno", false)));
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Dos", false)));

		assertThat(almacen.buscarPorId(2)).get().extracting(Tarea::texto).isEqualTo("Dos");
		assertThat(almacen.buscarPorId(99)).isEmpty();
	}

	@Test
	@DisplayName("consultarTodas devuelve una copia que no permite tocar el estado interno")
	void devuelveUnaCopiaInmutable() {
		AlmacenTareas almacen = nuevoAlmacen(this.archivo);
		almacen.modificar((tareas, reservarId) -> tareas.add(new Tarea(reservarId.getAsInt(), "Uno", false)));

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

	/**
	 * El camino correcto ya tenía prueba («no queda ningún archivo temporal tras un guardado
	 * correcto»), pero era justo el que no deja basura: al terminar bien, {@code Files.move} se ha
	 * llevado el temporal. El que sí la dejaba era este.
	 */
	@Test
	@DisplayName("un fallo de escritura tampoco deja el archivo temporal tirado")
	void borraElTemporalSiLaEscrituraFalla() throws Exception {
		Path rutaOcupada = this.directorio.resolve("ocupada.json");
		AlmacenTareas almacen = nuevoAlmacen(rutaOcupada);

		Files.createDirectory(rutaOcupada);
		Files.createDirectory(rutaOcupada.resolve("no-esta-vacio"));

		assertThatExceptionOfType(AlmacenamientoException.class)
			.isThrownBy(() -> almacen.modificar((tareas, reservarId) -> tareas
				.add(new Tarea(reservarId.getAsInt(), "Provoca el fallo", false))));

		assertThat(this.directorio.resolve("ocupada.json.tmp"))
			.as("el temporal debe limpiarse también cuando la escritura no llega a completarse")
			.doesNotExist();
	}

	/**
	 * En la frontera HTTP todo lo valida {@code @Valid}; en la del archivo no lo validaba nadie. Con
	 * ids repetidos, el {@code Collectors.toMap} de reordenar reventaba con un 500 muy lejos de la
	 * causa. Ahora se rechaza al cargar, y —esto es lo que comprueba la segunda aserción— se rechaza
	 * ANTES de tocar la lista, para no dejar el almacén con datos que el resto del código cree
	 * imposibles.
	 */
	@Test
	@DisplayName("un archivo con ids repetidos se rechaza sin dejar el almacén a medio cargar")
	void rechazaIdsRepetidosSinCargarNada() throws Exception {
		Files.writeString(this.archivo, """
				[{"id":1,"texto":"una","completada":false},
				 {"id":1,"texto":"otra","completada":false}]
				""", StandardCharsets.UTF_8);

		AlmacenTareas almacen = new AlmacenTareas(JsonMapper.builder().build(),
				new PropiedadesAlmacen(this.archivo.toString(), this.directorio.resolve("fondos.json").toString()));

		assertThatExceptionOfType(AlmacenamientoException.class).isThrownBy(almacen::cargarDesdeArchivo)
			.withMessageContaining("más de una vez");

		assertThat(almacen.consultarTodas()).as("no debe quedar nada cargado tras rechazar el archivo").isEmpty();
	}

}

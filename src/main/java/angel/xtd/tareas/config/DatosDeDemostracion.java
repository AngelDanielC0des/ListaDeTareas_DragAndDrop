package angel.xtd.tareas.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import angel.xtd.tareas.dto.Fondo;
import angel.xtd.tareas.dto.Tarea;
import angel.xtd.tareas.service.TareasService;
import jakarta.annotation.PostConstruct;

/**
 * Contenido de ejemplo para la demostración pública. Solo se activa con el perfil {@code demo}.
 *
 * <p><b>Por qué hace falta.</b> La demo es una única instancia compartida por todo el que entre: no
 * hay usuarios ni sesiones, así que todos ven y editan la misma lista. Eso trae dos problemas que
 * esta clase resuelve.
 *
 * <p>El primero es que quien llegue después de que alguien lo haya borrado todo se encuentra una
 * pantalla vacía y no ve nada de lo que la aplicación hace. Por eso se siembran unas tareas de
 * ejemplo, dos de ellas con fondo, para que se vea la reordenación y la decoración de un vistazo.
 *
 * <p>El segundo es que lo que un visitante escriba se queda ahí para el siguiente, y en una página
 * abierta a internet eso acaba siendo texto que no quieres enseñar. Por eso la lista se rehace sola
 * cada hora.
 *
 * <p>Nada de esto se activa en local ni en las pruebas: sin {@code --spring.profiles.active=demo},
 * esta clase no llega a instanciarse y la aplicación arranca con la lista que hubiera en el archivo.
 */
@Configuration
@Profile("demo")
@EnableScheduling
public class DatosDeDemostracion {

	private static final Logger log = LoggerFactory.getLogger(DatosDeDemostracion.class);

	private static final long UNA_HORA_EN_MILISEGUNDOS = 60 * 60 * 1000L;

	/**
	 * Una tarea de ejemplo con todo lo suyo junto.
	 *
	 * <p>Va en un record y no en tres listas paralelas para que no haya que casar índices: antes esto
	 * era una lista de textos y luego {@code creadas.get(1)} y {@code creadas.get(3)} para decidir
	 * cuál iba completada y cuáles llevaban fondo. Quitar un texto de la lista hacía reventar el
	 * arranque con un {@code IndexOutOfBounds}, y precisamente en el perfil de la demo, que es el
	 * único sitio donde nadie está mirando la consola.
	 */
	private record TareaDeEjemplo(String texto, boolean completada, Fondo fondo) {
	}

	/**
	 * Lo que ve alguien que abre la demo por primera vez.
	 *
	 * <p>Están elegidas para que la primera pantalla enseñe sola de qué va la aplicación: una que
	 * invita a arrastrar, otra ya completada para que se vea cómo se atenúa sin moverse de sitio y
	 * cómo cuenta en el progreso, una larga que se recorta y saca el botón «Ver más», y dos con
	 * fondo.
	 */
	private static final List<TareaDeEjemplo> EJEMPLOS = List.of(
			new TareaDeEjemplo("Arrástrame por el asa de la izquierda", false, Fondo.ONDAS),
			new TareaDeEjemplo("Pulsa la casilla para completarme", true, Fondo.NINGUNO),
			new TareaDeEjemplo("Este texto es deliberadamente largo para que se vea cómo la tarjeta lo recorta "
					+ "a dos líneas y aparece el botón «Ver más», que lo despliega sin mover el resto de la "
					+ "lista", false, Fondo.NINGUNO),
			new TareaDeEjemplo("Dame un fondo con el botón del paisaje", false, Fondo.AURORA),
			new TareaDeEjemplo("Bórrame: tendrás unos segundos para deshacerlo", false, Fondo.NINGUNO));

	private final TareasService servicio;

	public DatosDeDemostracion(TareasService servicio) {
		this.servicio = servicio;
	}

	/** Al arrancar solo se siembra si no hay nada, para no pisar lo que haya en el volumen. */
	@PostConstruct
	public void sembrarSiEstaVacia() {
		if (this.servicio.consultarTodas().isEmpty()) {
			sembrar();
		}
		else {
			log.info("Perfil demo: ya hay tareas, no se siembra nada");
		}
	}

	/**
	 * Rehace la lista cada hora.
	 *
	 * <p>Se usa {@code fixedRate} y no {@code cron} a propósito: lo que importa es que no pase mucho
	 * tiempo desde el arranque hasta la primera limpieza, no que ocurra a una hora concreta.
	 */
	@Scheduled(fixedRate = UNA_HORA_EN_MILISEGUNDOS, initialDelay = UNA_HORA_EN_MILISEGUNDOS)
	public void reiniciarPeriodicamente() {
		log.info("Perfil demo: reiniciando la lista de ejemplo");
		vaciar();
		sembrar();
	}

	private void vaciar() {
		for (Tarea tarea : this.servicio.consultarTodas()) {
			this.servicio.eliminar(tarea.id());
		}
	}

	private void sembrar() {
		for (TareaDeEjemplo ejemplo : EJEMPLOS) {
			Tarea creada = this.servicio.crear(ejemplo.texto());
			if (ejemplo.completada()) {
				this.servicio.cambiarCompletada(creada.id(), true);
			}
			if (ejemplo.fondo() != Fondo.NINGUNO) {
				this.servicio.cambiarFondo(creada.id(), ejemplo.fondo());
			}
		}
		log.info("Perfil demo: sembradas {} tareas de ejemplo", EJEMPLOS.size());
	}

}

/**
 * Reordenación de tareas: arrastre con puntero o dedo, y alternativa con teclado.
 *
 * SortableJS resuelve la parte cara —seguir el puntero en una rejilla de 2 a 4 columnas con
 * tarjetas de altura distinta, hacer sitio con animación, autoscroll al llegar al borde y no
 * confundir un arrastre con un scroll táctil—. Lo que no trae es soporte de teclado, así que esa
 * parte se añade aquí.
 *
 * Los dos caminos desembocan en la MISMA función `alMover`, de modo que solo existe una ruta de
 * código que reordena y una sola que llama a la API. La única diferencia es que SortableJS ya ha
 * movido el nodo cuando avisa, y el teclado no, y eso viaja como tercer argumento.
 */

import * as estado from './estado.js';
import * as vista from './vista.js';

const DURACION_ANIMACION = 150;

/**
 * Qué hacer cuando el usuario mueve una tarea.
 *
 * @callback AlMover
 * @param {number} desde
 * @param {number} hasta
 * @param {boolean} [elDomYaEstaMovido] SortableJS mueve el nodo antes de avisar; el teclado no
 * @returns {void}
 */

/** @param {{alMover: AlMover}} manejadores */
/**
 * La instancia de SortableJS, para poder desactivarla mientras haya un filtro puesto.
 *
 * Va sin tipo concreto porque la librería se carga con una etiqueta <script> y se cuelga de
 * globalThis: no hay import del que sacar sus tipos.
 *
 * @type {any}
 */
let arrastre = null;

/** @param {{alMover: AlMover}} manejadores */
export function iniciarReordenacion({ alMover }) {
	arrastre = crearArrastreConSortable(alMover);
	registrarReordenacionPorTeclado(alMover);
}

/**
 * Activa o desactiva el arrastre.
 *
 * Con un filtro puesto solo se ve parte de la lista, y arrastrar dentro de una lista parcial no
 * puede producir un orden completo coherente: las posiciones que ve el usuario no son las del
 * estado. Antes que inventar una correspondencia frágil, se desactiva y se explica en la interfaz.
 *
 * @param {boolean} permitido
 */
export function permitirReordenar(permitido) {
	arrastre?.option('disabled', !permitido);
	vista.lista.classList.toggle('lista--sin-arrastre', !permitido);
}

/**
 * @param {AlMover} alMover
 * @returns {any} la instancia de SortableJS, o null si la librería no está
 */
function crearArrastreConSortable(alMover) {
	// SortableJS se carga con una etiqueta <script> normal y se cuelga de globalThis, así que no hay
	// import del que sacar su tipo: se accede por índice y se documenta como `any`.
	const Sortable = /** @type {any} */ (globalThis)['Sortable'];
	if (!Sortable) {
		// La reordenación por teclado sigue funcionando, así que esto degrada en vez de romper.
		console.warn('SortableJS no está disponible: solo se podrá reordenar con el teclado.');
		return null;
	}

	const prefiereMenosMovimiento = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

	const resultado = Sortable.create(vista.lista, {
		handle: '.tarea__asa',
		draggable: '.tarea',
		animation: prefiereMenosMovimiento ? 0 : DURACION_ANIMACION,
		// Sin «easing» el hueco se abre a velocidad constante, que se nota mecánico. Esta curva
		// arranca rápido y frena al final, que es como se mueven las cosas con inercia.
		easing: 'cubic-bezier(0.2, 0, 0, 1)',
		ghostClass: 'tarea--fantasma',
		chosenClass: 'tarea--agarrada',
		// En táctil hace falta una pulsación sostenida para distinguir el arrastre del scroll;
		// con ratón el arrastre debe empezar de inmediato.
		delay: 100,
		delayOnTouchOnly: true,
		touchStartThreshold: 5,
		/** @param {{oldIndex: number, newIndex: number}} evento */
		onEnd: (evento) => {
			if (evento.oldIndex === evento.newIndex) {
				return;
			}
			alMover(evento.oldIndex, evento.newIndex, true);
		}
	});
	return resultado;
}

/**
 * Ctrl + flecha arriba/abajo sobre el asa. Se usa Ctrl porque las flechas solas deben seguir
 * sirviendo para desplazar la página.
 */
/** @param {AlMover} alMover */
function registrarReordenacionPorTeclado(alMover) {
	vista.lista.addEventListener('keydown', (evento) => {
		if (!(evento instanceof KeyboardEvent) || !(evento.target instanceof Element)) {
			return;
		}
		if (!evento.ctrlKey || (evento.key !== 'ArrowUp' && evento.key !== 'ArrowDown')) {
			return;
		}

		const asa = evento.target.closest('.tarea__asa');
		if (!asa) {
			return;
		}

		// La posición se pregunta al estado, no al DOM. `estado.js` abre diciendo que el DOM es una
		// proyección suya y que nunca se le consulta el orden; esta era la única grieta en esa regla.
		// El teclado se frena por el mismo motivo que el arrastre: con la lista filtrada, las
		// posiciones que se ven no son las del estado.
		if (estado.hayFiltroActivo()) {
			return;
		}

		const tarjeta = asa.closest('.tarea');
		if (!(tarjeta instanceof HTMLElement)) {
			return;
		}
		const id = Number(tarjeta.dataset.id);
		const desde = estado.buscarPosicionDeTarea(id);
		const total = estado.obtenerTareas().length;
		const hasta = (evento.key === 'ArrowUp') ? desde - 1 : desde + 1;
		if (desde === -1 || hasta < 0 || hasta >= total) {
			return;
		}

		evento.preventDefault();
		alMover(desde, hasta, false);

		// La tarjeta se ha movido de sitio en el DOM, pero es el mismo nodo: el foco lo sigue. Aun
		// así se reafirma, para que encadenar varios movimientos funcione en cualquier navegador.
		const asaDestino = vista.obtenerTarjetaDeTarea(id)?.querySelector('.tarea__asa');
		if (asaDestino instanceof HTMLElement) {
			asaDestino.focus();
		}
		vista.anunciar(`Tarea movida a la posición ${hasta + 1} de ${total}.`);
	});
}

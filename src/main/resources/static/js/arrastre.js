/**
 * Reordenación de tareas: arrastre con puntero o dedo, y alternativa con teclado.
 *
 * SortableJS resuelve la parte cara —seguir el puntero, hacer sitio con animación, autoscroll al
 * llegar al borde y no confundir un arrastre con un scroll táctil—. Lo que no trae es soporte de
 * teclado, así que esa parte se añade aquí.
 *
 * Con grupos hay **una lista por sección**, todas conectadas entre sí, de modo que arrastrar una
 * tarjeta a otra sección la cambia de grupo. Como las secciones se repintan enteras, las instancias
 * de SortableJS hay que rehacerlas después de cada pintado.
 *
 * De lo que SortableJS informa solo se toman tres datos —qué tarea, a qué sección y a qué posición
 * dentro de ella—; el orden completo lo recompone `estado.js`. Leer el orden del DOM sería más corto
 * y rompería la regla que sostiene todo el cliente.
 */

import * as estado from './estado.js';
import * as vista from './vista.js';

const DURACION_ANIMACION = 150;

/**
 * Qué hacer cuando el usuario mueve una tarea.
 *
 * @callback AlMover
 * @param {number} id
 * @param {number | null} idGrupo sección de destino, o null si son las tareas sueltas
 * @param {number} posicionEnGrupo
 * @returns {void}
 */

/** Las instancias vivas, para poder deshacerlas antes de rehacerlas. @type {any[]} */
let instancias = [];

/** @type {AlMover | null} */
let alMoverGuardado = null;

/** Si el arrastre está permitido. Con un filtro puesto no lo está. @type {boolean} */
let permitido = true;

/** @param {{alMover: AlMover}} manejadores */
export function iniciarReordenacion({ alMover }) {
	alMoverGuardado = alMover;
	registrarReordenacionPorTeclado(alMover);
	rehacer();
}

/**
 * Vuelve a enganchar el arrastre a las listas que hay ahora.
 *
 * Hay que llamarlo después de cada repintado: `pintarLista()` reemplaza las secciones enteras, y las
 * instancias viejas quedarían apuntando a nodos que ya no están en el documento.
 */
export function rehacer() {
	for (const instancia of instancias) {
		instancia.destroy();
	}
	instancias = [];

	const alMover = alMoverGuardado;
	if (alMover === null) {
		return;
	}

	const Sortable = /** @type {any} */ (globalThis)['Sortable'];
	if (!Sortable) {
		// La reordenación por teclado sigue funcionando, así que esto degrada en vez de romper.
		console.warn('SortableJS no está disponible: solo se podrá reordenar con el teclado.');
		return;
	}

	const prefiereMenosMovimiento = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

	for (const lista of vista.secciones.querySelectorAll('.lista')) {
		instancias.push(Sortable.create(lista, {
			// El mismo nombre de grupo en todas las listas es lo que las conecta entre sí y permite
			// arrastrar una tarjeta de una sección a otra.
			group: 'tareas',
			handle: '.tarea__asa',
			draggable: '.tarea',
			disabled: !permitido,
			animation: prefiereMenosMovimiento ? 0 : DURACION_ANIMACION,
			easing: 'cubic-bezier(0.2, 0, 0, 1)',
			ghostClass: 'tarea--fantasma',
			chosenClass: 'tarea--agarrada',
			// En táctil hace falta una pulsación sostenida para distinguir el arrastre del scroll;
			// con ratón el arrastre debe empezar de inmediato.
			delay: 100,
			delayOnTouchOnly: true,
			touchStartThreshold: 5,
			onEnd: (/** @type {any} */ evento) => {
				const noSeHaMovido = evento.from === evento.to && evento.oldIndex === evento.newIndex;
				if (noSeHaMovido) {
					return;
				}
				const id = Number(evento.item.dataset.id);
				alMover(id, grupoDeLaLista(evento.to), evento.newIndex);
			}
		}));
	}
}

/**
 * Activa o desactiva el arrastre.
 *
 * Con un filtro puesto solo se ve parte de la lista, y arrastrar dentro de una lista parcial no
 * puede producir un orden completo coherente: las posiciones que ve el usuario no son las del
 * estado. Antes que inventar una correspondencia frágil, se desactiva y se explica en la interfaz.
 *
 * @param {boolean} sePuede
 */
export function permitirReordenar(sePuede) {
	permitido = sePuede;
	for (const instancia of instancias) {
		instancia.option('disabled', !sePuede);
	}
	vista.secciones.classList.toggle('secciones--sin-arrastre', !sePuede);
}

/**
 * A qué grupo pertenece una lista, o `null` si es la de las tareas sueltas.
 *
 * @param {Element} lista
 * @returns {number | null}
 */
function grupoDeLaLista(lista) {
	const seccion = lista.closest('.seccion');
	if (!(seccion instanceof HTMLElement)) {
		return null;
	}
	const bruto = seccion.dataset.grupo ?? '';
	const resultado = (bruto === '') ? null : Number(bruto);
	return resultado;
}

/**
 * Ctrl + flecha arriba/abajo sobre el asa mueve la tarea dentro de su sección, y Ctrl + flecha
 * izquierda/derecha la cambia de sección.
 *
 * Se usa Ctrl porque las flechas solas deben seguir sirviendo para desplazar la página. Y las
 * flechas laterales existen porque sin ellas cambiar de grupo solo se podría con el ratón, que
 * dejaría fuera a quien navega con teclado.
 *
 * @param {AlMover} alMover
 */
function registrarReordenacionPorTeclado(alMover) {
	vista.secciones.addEventListener('keydown', (evento) => {
		if (!(evento instanceof KeyboardEvent) || !(evento.target instanceof Element)) {
			return;
		}
		const esFlecha = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(evento.key);
		if (!evento.ctrlKey || !esFlecha) {
			return;
		}

		const asa = evento.target.closest('.tarea__asa');
		if (!asa) {
			return;
		}

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
		const grupoActual = estado.obtenerGrupoDe(id);

		if (evento.key === 'ArrowUp' || evento.key === 'ArrowDown') {
			moverDentroDeLaSeccion(evento, alMover, id, grupoActual);
		}
		else {
			moverAOtraSeccion(evento, alMover, id, grupoActual);
		}
	});
}

/**
 * @param {KeyboardEvent} evento
 * @param {AlMover} alMover
 * @param {number} id
 * @param {number | null} grupoActual
 */
function moverDentroDeLaSeccion(evento, alMover, id, grupoActual) {
	const enSuGrupo = estado.obtenerTareasDeGrupo(grupoActual);
	const desde = enSuGrupo.findIndex((tarea) => tarea.id === id);
	const hasta = (evento.key === 'ArrowUp') ? desde - 1 : desde + 1;

	if (desde === -1 || hasta < 0 || hasta >= enSuGrupo.length) {
		return;
	}

	evento.preventDefault();
	alMover(id, grupoActual, hasta);
	anunciarYReenfocar(id, `Movida a la posición ${hasta + 1} de ${enSuGrupo.length}.`);
}

/**
 * Cambia la tarea a la sección anterior o siguiente.
 *
 * El recorrido incluye las tareas sueltas como una sección más, al final, que es donde se pintan.
 *
 * @param {KeyboardEvent} evento
 * @param {AlMover} alMover
 * @param {number} id
 * @param {number | null} grupoActual
 */
function moverAOtraSeccion(evento, alMover, id, grupoActual) {
	/** @type {(number | null)[]} */
	const recorrido = [...estado.obtenerGrupos().map((grupo) => grupo.id), null];
	const desde = recorrido.indexOf(grupoActual);
	const hasta = (evento.key === 'ArrowLeft') ? desde - 1 : desde + 1;

	if (desde === -1 || hasta < 0 || hasta >= recorrido.length) {
		return;
	}

	evento.preventDefault();
	const destino = recorrido[hasta];
	alMover(id, destino, estado.obtenerTareasDeGrupo(destino).length);

	const nombre = estado.obtenerGrupos().find((grupo) => grupo.id === destino)?.nombre ?? 'Sin grupo';
	anunciarYReenfocar(id, `Movida al grupo ${nombre}.`);
}

/**
 * Devuelve el foco al asa de la tarjeta y anuncia lo que ha pasado.
 *
 * La tarjeta se ha vuelto a pintar, así que el nodo con el foco ya no existe: hay que buscar el
 * nuevo. Sin esto, encadenar varios movimientos con el teclado sería imposible.
 *
 * @param {number} id
 * @param {string} mensaje
 */
function anunciarYReenfocar(id, mensaje) {
	const asaDestino = vista.obtenerTarjetaDeTarea(id)?.querySelector('.tarea__asa');
	if (asaDestino instanceof HTMLElement) {
		asaDestino.focus();
	}
	vista.anunciar(mensaje);
}

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

import * as vista from './vista.js';

const DURACION_ANIMACION = 150;

export function iniciarReordenacion({ alMover }) {
	crearArrastreConSortable(alMover);
	registrarReordenacionPorTeclado(alMover);
}

function crearArrastreConSortable(alMover) {
	const Sortable = globalThis.Sortable;
	if (!Sortable) {
		// La reordenación por teclado sigue funcionando, así que esto degrada en vez de romper.
		console.warn('SortableJS no está disponible: solo se podrá reordenar con el teclado.');
		return;
	}

	const prefiereMenosMovimiento = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

	Sortable.create(vista.lista, {
		handle: '.tarea__asa',
		draggable: '.tarea',
		animation: prefiereMenosMovimiento ? 0 : DURACION_ANIMACION,
		ghostClass: 'tarea--fantasma',
		chosenClass: 'tarea--agarrada',
		// En táctil hace falta una pulsación sostenida para distinguir el arrastre del scroll;
		// con ratón el arrastre debe empezar de inmediato.
		delay: 100,
		delayOnTouchOnly: true,
		touchStartThreshold: 5,
		onEnd: (evento) => {
			if (evento.oldIndex === evento.newIndex) {
				return;
			}
			alMover(evento.oldIndex, evento.newIndex, true);
		}
	});
}

/**
 * Ctrl + flecha arriba/abajo sobre el asa. Se usa Ctrl porque las flechas solas deben seguir
 * sirviendo para desplazar la página.
 */
function registrarReordenacionPorTeclado(alMover) {
	vista.lista.addEventListener('keydown', (evento) => {
		if (!evento.ctrlKey || (evento.key !== 'ArrowUp' && evento.key !== 'ArrowDown')) {
			return;
		}

		const asa = evento.target.closest('.tarea__asa');
		if (!asa) {
			return;
		}

		const tarjeta = asa.closest('.tarea');
		const desde = [...vista.lista.children].indexOf(tarjeta);
		const hasta = (evento.key === 'ArrowUp') ? desde - 1 : desde + 1;
		if (hasta < 0 || hasta >= vista.lista.children.length) {
			return;
		}

		evento.preventDefault();
		const id = Number(tarjeta.dataset.id);
		alMover(desde, hasta, false);

		// La tarjeta se ha movido de sitio en el DOM, pero es el mismo nodo: el foco lo sigue. Aun
		// así se reafirma, para que encadenar varios movimientos funcione en cualquier navegador.
		vista.obtenerTarjetaDeTarea(id)?.querySelector('.tarea__asa')?.focus();
		vista.anunciar(`Tarea movida a la posición ${hasta + 1} de ${vista.lista.children.length}.`);
	});
}

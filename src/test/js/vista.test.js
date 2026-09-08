// @vitest-environment jsdom

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeAll, beforeEach, describe, expect, it } from 'vitest';

/**
 * Pruebas de la capa que toca el DOM.
 *
 * **Se carga el `index.html` de verdad**, no un HTML inventado para la ocasión. Esa es la diferencia
 * con `RecursosEstaticosTest`, que desde Java solo puede buscar cadenas: aquí, si alguien renombra
 * una clase de la plantilla o quita un elemento, el módulo falla igual que fallaría en el navegador.
 *
 * Los módulos se importan dentro de `beforeAll` y no arriba porque `vista.js` busca sus elementos al
 * cargarse: si se importara antes de montar el DOM, lanzaría al instante.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const INDEX = resolve(AQUI, '../../main/resources/static/index.html');

/** @type {import('../../main/resources/static/js/vista.js')} */
let vista;

/** @type {import('../../main/resources/static/js/estado.js')} */
let estado;

/** El cuerpo del HTML real, sin los <script>, que aquí no hacen falta y solo darían ruido. */
function cuerpoDeIndex() {
	const html = readFileSync(INDEX, 'utf8');
	const cuerpo = html.slice(html.indexOf('<body>') + '<body>'.length, html.indexOf('</body>'));
	return cuerpo.replace(/<script[\s\S]*?<\/script>/g, '');
}

/** @type {import('../../main/resources/static/js/tipos.js').Tarea[]} */
const DOS_TAREAS = [
	{ id: 1, texto: 'Comprar pan', completada: false },
	{ id: 2, texto: 'Regar plantas', completada: true }
];

beforeAll(async () => {
	document.body.innerHTML = cuerpoDeIndex();
	estado = await import('../../main/resources/static/js/estado.js');
	vista = await import('../../main/resources/static/js/vista.js');
});

beforeEach(() => {
	estado.reemplazarFondos({});
	estado.terminarEdicion();
	estado.reemplazarTareas(DOS_TAREAS);
	estado.reemplazarGrupos({ grupos: [], asignaciones: {} });
	estado.filtrarPorEstado('todas');
	estado.buscar('');
	vista.pintarLista();
});

/** Los ids de las tarjetas, en el orden en que están pintadas. */
function idsPintados() {
	const tarjetas = /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.seccion .tarea'));
	return [...tarjetas].map((t) => t.dataset.id);
}

/** @param {number} id */
function tarjeta(id) {
	const encontrada = vista.obtenerTarjetaDeTarea(id);
	if (encontrada === null) {
		throw new Error(`no se ha pintado la tarjeta ${id}`);
	}
	return encontrada;
}

describe('pintar la lista', () => {

	it('pinta una tarjeta por tarea, en orden', () => {
		expect(idsPintados()).toEqual(['1', '2']);
	});

	it('vuelca el texto con textContent, así que el marcado del usuario no se interpreta', () => {
		estado.reemplazarTareas([{ id: 1, texto: '<img src=x onerror=alert(1)>', completada: false }]);
		vista.pintarLista();

		const parrafo = tarjeta(1).querySelector('.tarea__texto');
		expect(parrafo?.querySelector('img')).toBeNull();
		expect(parrafo?.textContent).toBe('<img src=x onerror=alert(1)>');
	});

	it('marca visualmente las tareas completadas', () => {
		expect(tarjeta(2).classList.contains('tarea--completada')).toBe(true);
		expect(tarjeta(1).classList.contains('tarea--completada')).toBe(false);
	});

	it('esconde el estado vacío cuando hay tareas y lo enseña cuando no', () => {
		const vacio = /** @type {HTMLElement} */ (document.getElementById('lista-vacia'));
		expect(vacio.hidden).toBe(true);

		estado.reemplazarTareas([]);
		vista.pintarLista();

		expect(vacio.hidden).toBe(false);
	});

	/** Cada tarjeta necesita un nombre propio para que el navegador la anime al moverla. */
	it('da a cada tarjeta un nombre de transición único', () => {
		expect(tarjeta(1).style.viewTransitionName).toBe('tarea-1');
		expect(tarjeta(2).style.viewTransitionName).toBe('tarea-2');
	});

});

describe('accesibilidad de la tarjeta', () => {

	/**
	 * Sin esto, un lector de pantalla recorre la lista diciendo «Editar, botón», «Borrar, botón» sin
	 * nombrar nunca a qué tarea pertenecen.
	 */
	it.each([
		['editar', 'Editar la tarea «Comprar pan»'],
		['eliminar', 'Borrar la tarea «Comprar pan»'],
		['fondo', 'Elegir el fondo de la tarea «Comprar pan»']
	])('el botón de %s dice de qué tarea es', (accion, esperado) => {
		const boton = tarjeta(1).querySelector(`[data-accion="${accion}"]`);

		expect(boton?.getAttribute('aria-label')).toBe(esperado);
	});

	it('la etiqueta de la casilla dice qué va a pasar al pulsarla', () => {
		expect(tarjeta(1).querySelector('label')?.textContent)
			.toBe('Marcar «Comprar pan» como completada');
		expect(tarjeta(2).querySelector('label')?.textContent)
			.toBe('Marcar «Regar plantas» como pendiente');
	});

	it('la etiqueta apunta a su casilla mediante un id único por tarea', () => {
		const casilla = tarjeta(2).querySelector('.tarea__casilla');
		const etiqueta = tarjeta(2).querySelector('label');

		expect(casilla?.id).toBe('casilla-2');
		expect(etiqueta?.htmlFor).toBe('casilla-2');
	});

});

/**
 * Si una parte de la tarjeta está oculta.
 *
 * @param {number} id
 * @param {string} selector
 */
function oculto(id, selector) {
	const parte = /** @type {HTMLElement | null} */ (tarjeta(id).querySelector(selector));
	return parte?.hidden;
}

describe('modo edición', () => {

	it('cambia el párrafo por el cuadro de edición y solo en esa tarjeta', () => {
		estado.empezarEdicionDe(1, 'Comprar pan');
		vista.pintarLista();

		expect(oculto(1, '.tarea__texto')).toBe(true);
		expect(oculto(1, '.tarea__editor')).toBe(false);
		expect(oculto(2, '.tarea__texto')).toBe(false);
		expect(oculto(2, '.tarea__editor')).toBe(true);
	});

	it('el botón pasa a decir «Listo» mientras se edita', () => {
		estado.empezarEdicionDe(1, 'Comprar pan');
		vista.pintarLista();

		const texto = tarjeta(1).querySelector('[data-accion="editar"] .tarea__accion-texto');
		expect(texto?.textContent).toBe('Listo');
	});

	/**
	 * REGRESIÓN. El texto del botón se escribía con `boton.textContent`, que borra TODOS los hijos:
	 * se llevaba por delante el icono y el <span> que lo envuelve. En escritorio «Editar» era el
	 * único botón sin dibujo, y por debajo de 480 px —donde el CSS oculta ese span para dejar solo
	 * iconos— aparecía la palabra suelta mientras sus dos vecinos eran pictogramas.
	 */
	it('escribir el texto del botón no se lleva por delante su icono', () => {
		const boton = tarjeta(1).querySelector('[data-accion="editar"]');

		expect(boton?.querySelector('svg.icono')).not.toBeNull();
		expect(boton?.querySelector('.tarea__accion-texto')).not.toBeNull();

		estado.empezarEdicionDe(1, 'Comprar pan');
		vista.actualizarTarjeta(1);

		expect(boton?.querySelector('svg.icono')).not.toBeNull();
		expect(boton?.querySelector('.tarea__accion-texto')).not.toBeNull();
	});

});

describe('resumen y progreso', () => {

	it('cuenta las completadas y refleja el progreso', () => {
		expect(document.getElementById('resumen')?.textContent).toBe('1 de 2 completadas.');

		const progreso = /** @type {HTMLProgressElement} */ (document.getElementById('progreso'));
		expect(progreso.max).toBe(2);
		expect(progreso.value).toBe(1);
	});

	it('lleva las pendientes al título de la pestaña', () => {
		expect(document.title).toBe('(1) Mis tareas');

		estado.reemplazarTareas([{ id: 1, texto: 'una', completada: true }]);
		vista.pintarLista();

		expect(document.title).toBe('Mis tareas');
	});

    it('con la lista vacía no divide por cero ni deja la barra a medias', () => {
		estado.reemplazarTareas([]);
		vista.pintarLista();

		const progreso = /** @type {HTMLProgressElement} */ (document.getElementById('progreso'));
		expect(document.getElementById('resumen')?.textContent).toBe('Sin tareas.');
		expect(progreso.max).toBe(1);
		expect(progreso.value).toBe(0);
		expect(progreso.hidden).toBe(true);
	});

});

describe('fondos', () => {

	it('aplica la clase del fondo elegido y la marca de «tiene fondo»', () => {
		estado.reemplazarFondos({ 1: 'ondas' });
		vista.pintarLista();

		expect(tarjeta(1).classList.contains('tarea--fondo-ondas')).toBe(true);
		expect(tarjeta(1).classList.contains('tarea--con-fondo')).toBe(true);
	});

	/** «Sin fondo» es la ausencia de las demás clases, no una clase propia. */
	it('una tarea sin fondo no recibe ninguna clase de fondo', () => {
		const clases = [...tarjeta(1).classList];

		expect(clases.filter((c) => c.startsWith('tarea--fondo-'))).toEqual([]);
		expect(tarjeta(1).classList.contains('tarea--con-fondo')).toBe(false);
	});

});

describe('secciones y grupos', () => {

	/** @param {string} selector */
	function texto(selector) {
		return document.querySelector(selector)?.textContent;
	}

	beforeEach(() => {
		estado.reemplazarGrupos({ grupos: [{ id: 1, nombre: 'Mañana' }], asignaciones: { 1: 1 } });
		vista.pintarLista();
	});

	it('pinta una sección por grupo, más la de las tareas sueltas', () => {
		const nombres = [...document.querySelectorAll('.seccion__nombre')].map((n) => n.textContent);

		expect(nombres).toEqual(['Mañana', 'Sin grupo']);
	});

	it('mete cada tarea en la sección de su grupo', () => {
		const secciones = [...document.querySelectorAll('.seccion')];
		const idsPorSeccion = secciones.map((seccion) => {
			const tarjetas = /** @type {NodeListOf<HTMLElement>} */ (seccion.querySelectorAll('.tarea'));
			return [...tarjetas].map((t) => t.dataset.id);
		});

		expect(idsPorSeccion).toEqual([['1'], ['2']]);
	});

	it('la cabecera del grupo lleva la cuenta de completadas', () => {
		expect(texto('.seccion__cuenta')).toBe('0 / 1');
	});

	it('la barra de progreso del grupo avanza al completar', () => {
		estado.reemplazarTareas([{ id: 1, texto: 'Comprar pan', completada: true }]);
		vista.pintarLista();

		const progreso = /** @type {HTMLProgressElement} */ (document.querySelector('.seccion__progreso'));
		expect(progreso.max).toBe(1);
		expect(progreso.value).toBe(1);
		expect(texto('.seccion__cuenta')).toBe('1 / 1');
	});

	/**
	 * Las sueltas no son un conjunto que se pueda dar por terminado, así que una barra ahí no
	 * significaría nada.
	 */
	it('las tareas sueltas no llevan barra de progreso', () => {
		const secciones = [...document.querySelectorAll('.seccion')];
		const sueltas = secciones[secciones.length - 1];

		const progreso = /** @type {HTMLProgressElement} */ (sueltas.querySelector('.seccion__progreso'));
		expect(progreso.hidden).toBe(true);
		const acciones = /** @type {HTMLElement} */ (sueltas.querySelector('.seccion__acciones'));
		expect(acciones.hidden).toBe(true);
	});

	it('no pinta la sección de sueltas si no queda ninguna y hay grupos', () => {
		estado.reemplazarGrupos({ grupos: [{ id: 1, nombre: 'Mañana' }], asignaciones: { 1: 1, 2: 1 } });
		vista.pintarLista();

		const nombres = [...document.querySelectorAll('.seccion__nombre')].map((n) => n.textContent);
		expect(nombres).toEqual(['Mañana']);
	});

	it('cada sección se anuncia con su nombre para quien navega con lector de pantalla', () => {
		const seccion = document.querySelector('.seccion');
		const etiqueta = seccion?.getAttribute('aria-labelledby');

		expect(document.getElementById(String(etiqueta))?.textContent).toBe('Mañana');
	});

	it('las acciones del grupo dicen de qué grupo son', () => {
		expect(document.querySelector('[data-accion="borrar-grupo"]')?.getAttribute('aria-label'))
			.toContain('Mañana');
	});

	/**
	 * REGRESIÓN. Marcar una tarea no repinta la lista —sería rehacer todas las tarjetas para cambiar
	 * una—, así que la cabecera se quedaba con la cuenta y la barra de antes. La barra de progreso
	 * existe justo para esto: si no se mueve al completar, no sirve de nada.
	 */
	it('completar una tarea mueve la barra de progreso de su grupo sin repintar la lista', () => {
		expect(texto('.seccion__cuenta')).toBe('0 / 1');

		estado.reemplazarTarea({ id: 1, texto: 'Comprar pan', completada: true });
		vista.actualizarTarjeta(1);

		expect(texto('.seccion__cuenta')).toBe('1 / 1');
		const progreso = /** @type {HTMLProgressElement} */ (document.querySelector('.seccion__progreso'));
		expect(progreso.value).toBe(1);
	});

	it('un grupo vacío invita a arrastrar tareas hasta él', () => {
		estado.reemplazarGrupos({ grupos: [{ id: 9, nombre: 'Casa' }], asignaciones: {} });
		vista.pintarLista();

		const casa = document.querySelector('[data-grupo="9"]');
		expect(/** @type {HTMLElement} */ (casa?.querySelector('.seccion__vacia')).hidden).toBe(false);
	});

});

describe('alta plegable', () => {

	it('arranca plegada tras el botón «+»', () => {
		const formulario = /** @type {HTMLElement} */ (document.getElementById('formulario-nueva'));
		expect(formulario.hidden).toBe(true);
		expect(vista.estaAbiertaElAlta()).toBe(false);
	});

	it('al abrirla enseña el formulario y esconde el botón', () => {
		vista.mostrarAlta(true);

		expect(vista.estaAbiertaElAlta()).toBe(true);
		expect(vista.botonAbrirAlta.hidden).toBe(true);
		expect(vista.botonAbrirAlta.getAttribute('aria-expanded')).toBe('true');
	});

	/** Si el foco se quedara en un elemento que se acaba de ocultar, se perdería en el body. */
	it('al cerrarla vuelve el foco al botón y se limpia lo escrito', () => {
		vista.mostrarAlta(true);
		vista.campoTexto.value = 'a medio escribir';

		vista.mostrarAlta(false);

		expect(vista.campoTexto.value).toBe('');
		expect(document.activeElement).toBe(vista.botonAbrirAlta);
		expect(vista.botonAbrirAlta.getAttribute('aria-expanded')).toBe('false');
	});

});

describe('filtro en la interfaz', () => {

	/** @param {string} id */
	function visible(id) {
		const elemento = /** @type {HTMLElement} */ (document.getElementById(id));
		return !elemento.hidden;
	}

	it('esconde los controles de filtro cuando no hay ninguna tarea', () => {
		estado.reemplazarTareas([]);
		vista.pintarLista();

		expect(visible('filtros')).toBe(false);
	});

	it('los enseña en cuanto hay tareas', () => {
		expect(visible('filtros')).toBe(true);
	});

	/** No es lo mismo «no tienes tareas» que «tu búsqueda no encuentra nada». */
	it('distingue la lista vacía de la búsqueda sin resultados', () => {
		estado.buscar('no existe');
		vista.pintarLista();

		expect(visible('lista-vacia')).toBe(false);
		expect(visible('sin-resultados')).toBe(true);
		expect(document.getElementById('sin-resultados')?.textContent)
			.toBe('Ninguna tarea coincide con «no existe».');
	});

	it('el mensaje explica cuál es el filtro que no encuentra nada', () => {
		estado.reemplazarTareas([{ id: 1, texto: 'una', completada: false }]);
		estado.filtrarPorEstado('completadas');
		vista.pintarLista();

		expect(document.getElementById('sin-resultados')?.textContent)
			.toBe('Todavía no has completado ninguna tarea.');
	});

	it('pinta solo las tareas que pasan el filtro', () => {
		estado.filtrarPorEstado('completadas');
		vista.pintarLista();

		expect(idsPintados()).toEqual(['2']);
	});

	/** Con la lista parcial las posiciones mienten, así que hay que decir que no se puede reordenar. */
	it('avisa de que el arrastre está desactivado mientras haya filtro', () => {
		expect(visible('aviso-arrastre')).toBe(false);

		estado.filtrarPorEstado('pendientes');
		vista.pintarLista();

		expect(visible('aviso-arrastre')).toBe(true);
	});

	it('resume los resultados para quien no ve la lista', () => {
		expect(vista.describirResultados()).toBe('2 tareas.');

		estado.filtrarPorEstado('completadas');
		expect(vista.describirResultados()).toBe('1 de 2 tareas.');

		estado.buscar('no existe');
		expect(vista.describirResultados()).toBe('Ninguna tarea coincide con «no existe».');
	});

});

describe('ayuda de atajos', () => {

	/**
	 * jsdom no implementa showModal(), así que se sustituyen por lo que hace falta: cambiar `open`.
	 * Lo que se comprueba aquí no es la ventana modal del navegador sino que alternar la abre y la
	 * cierra, que es la lógica nuestra.
	 */
	it('la misma acción abre y cierra la ayuda', () => {
		const dialogo = /** @type {HTMLDialogElement} */ (document.getElementById('atajos'));
		dialogo.showModal = () => { dialogo.open = true; };
		dialogo.close = () => { dialogo.open = false; };

		vista.alternarAtajos();
		expect(dialogo.open).toBe(true);

		vista.alternarAtajos();
		expect(dialogo.open).toBe(false);
	});

});

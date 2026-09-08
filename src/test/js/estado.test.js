import { beforeEach, describe, expect, it } from 'vitest';

import * as estado from '../../main/resources/static/js/estado.js';

/**
 * Pruebas de la fuente de verdad del cliente.
 *
 * Es el módulo con más lógica y el único sin DOM de por medio, así que se puede comprobar entero sin
 * montar nada. Lo que se busca aquí no es el camino feliz —que se ve a simple vista— sino los bordes:
 * mover a una posición que no existe, borrar una tarea que se estaba editando, o que una respuesta
 * del servidor deje rastro de tareas que ya no están.
 */

/** @type {import('../../main/resources/static/js/tipos.js').Tarea[]} */
const TRES_TAREAS = [
	{ id: 1, texto: 'una', completada: false },
	{ id: 2, texto: 'dos', completada: true },
	{ id: 3, texto: 'tres', completada: false }
];

/** El módulo guarda su estado entre pruebas, así que cada una arranca desde el mismo sitio. */
beforeEach(() => {
	estado.reemplazarTareas(TRES_TAREAS);
	estado.reemplazarFondos({});
	estado.terminarEdicion();
	estado.filtrarPorEstado('todas');
	estado.buscar('');
});

describe('orden de las tareas', () => {

	/** Es la que usa «Deshacer»: la tarea tiene que volver a su sitio, no al final. */
	it('reinserta una tarea en su posición original', () => {
		estado.quitarTarea(2);

		estado.insertarTareaEn(1, { id: 2, texto: 'dos', completada: true });

		expect(estado.obtenerIdsEnOrden()).toEqual([1, 2, 3]);
	});

	it('acota la posición al reinsertar en lugar de dejar un hueco', () => {
		estado.insertarTareaEn(99, { id: 4, texto: 'cuatro', completada: false });
		estado.insertarTareaEn(-5, { id: 5, texto: 'cinco', completada: false });

		expect(estado.obtenerIdsEnOrden()).toEqual([5, 1, 2, 3, 4]);
	});

});

describe('limpieza del estado de vista', () => {

	/**
	 * Sin esto, borrar una tarea desplegada y crear otra que reciba su id haría que la nueva naciera
	 * desplegada sin que nadie lo hubiera pedido.
	 */
	it('al quitar una tarea olvida que estaba desplegada', () => {
		estado.alternarDesplegada(2);
		expect(estado.estaDesplegada(2)).toBe(true);

		estado.quitarTarea(2);

		expect(estado.estaDesplegada(2)).toBe(false);
	});

	it('al quitar la tarea que se estaba editando cancela la edición', () => {
		estado.empezarEdicionDe(2, 'dos');

		estado.quitarTarea(2);

		expect(estado.obtenerIdEnEdicion()).toBeNull();
	});

	/** Una respuesta del servidor con menos tareas no debe dejar rastro de las que ya no están. */
	it('al reemplazar la lista descarta el estado de las tareas que desaparecen', () => {
		estado.alternarDesplegada(3);
		estado.empezarEdicionDe(3, 'tres');

		estado.reemplazarTareas([TRES_TAREAS[0]]);

		expect(estado.estaDesplegada(3)).toBe(false);
		expect(estado.obtenerIdEnEdicion()).toBeNull();
	});

	it('al reemplazar la lista conserva la edición si esa tarea sigue estando', () => {
		estado.empezarEdicionDe(1, 'una');

		estado.reemplazarTareas(TRES_TAREAS);

		expect(estado.obtenerIdEnEdicion()).toBe(1);
	});

});

describe('edición en curso', () => {

	/**
	 * `registrarTextoGuardado` lo llama la respuesta del servidor, que puede llegar cuando el usuario
	 * ya está editando otra tarea. Aplicarla entonces corrompería la comparación que decide si hace
	 * falta reenviar el texto.
	 */
	it('ignora el texto confirmado si ya se está editando otra tarea', () => {
		estado.empezarEdicionDe(1, 'una');
		estado.empezarEdicionDe(2, 'dos');

		estado.registrarTextoGuardado(1, 'llega tarde');

		expect(estado.obtenerTextoGuardadoDeEdicion()).toBe('dos');
	});

	it('guarda el texto original para poder cancelar con Escape', () => {
		estado.empezarEdicionDe(1, 'texto original');
		estado.reemplazarTarea({ id: 1, texto: 'a medio escribir', completada: false });

		expect(estado.obtenerTextoOriginalDeEdicion()).toBe('texto original');
		expect(estado.buscarTareaPorId(1)?.texto).toBe('a medio escribir');
	});

});

describe('fondos', () => {

	/** El servidor manda las claves como cadenas; aquí se consultan con el id numérico. */
	it('encuentra el fondo aunque la clave del servidor venga como cadena', () => {
		estado.reemplazarFondos({ 2: 'ondas' });

		expect(estado.obtenerFondoDe(2)).toBe('ondas');
	});

	it('devuelve «sin fondo» para una tarea que no tiene ninguno', () => {
		expect(estado.obtenerFondoDe(1)).toBe(estado.SIN_FONDO);
	});

});

describe('copias defensivas', () => {

	it('copiarTareas devuelve objetos nuevos que no tocan el estado', () => {
		const copia = estado.copiarTareas();
		copia[0].texto = 'modificado en la copia';

		expect(estado.buscarTareaPorId(1)?.texto).toBe('una');
	});

});

describe('filtro y búsqueda', () => {

	it('sin filtro se ven todas', () => {
		expect(estado.obtenerTareasVisibles()).toHaveLength(3);
		expect(estado.hayFiltroActivo()).toBe(false);
	});

	it('filtra por pendientes y por completadas', () => {
		estado.filtrarPorEstado('pendientes');
		expect(estado.obtenerTareasVisibles().map((t) => t.id)).toEqual([1, 3]);

		estado.filtrarPorEstado('completadas');
		expect(estado.obtenerTareasVisibles().map((t) => t.id)).toEqual([2]);
	});

	it('busca por texto sin distinguir mayúsculas', () => {
		estado.buscar('DOS');

		expect(estado.obtenerTareasVisibles().map((t) => t.id)).toEqual([2]);
	});

	/**
	 * Buscar en español sin esto falla justo con las palabras más propias del idioma: quien escribe
	 * «anadir» espera encontrar «añadir», y quien escribe «cafe» espera encontrar «café».
	 */
	it.each([
		['sin tilde encuentra con tilde', 'cafe'],
		['con tilde encuentra con tilde', 'café'],
		['la eñe se ignora igual', 'manana']
	])('%s', (_caso, buscado) => {
		estado.reemplazarTareas([{ id: 9, texto: 'Comprar café para mañana', completada: false }]);

		estado.buscar(buscado);

		expect(estado.obtenerTareasVisibles()).toHaveLength(1);
	});

	it('combina el estado con la búsqueda', () => {
		estado.filtrarPorEstado('pendientes');
		estado.buscar('tres');

		expect(estado.obtenerTareasVisibles().map((t) => t.id)).toEqual([3]);
	});

	it('devuelve la lista vacía cuando nada coincide, sin romperse', () => {
		estado.buscar('no existe esto');

		expect(estado.obtenerTareasVisibles()).toEqual([]);
	});

	/** Es lo que decide si se puede reordenar: con la lista parcial, las posiciones mienten. */
	it.each([
		['al filtrar por estado', () => estado.filtrarPorEstado('completadas')],
		['al buscar texto', () => estado.buscar('dos')]
	])('detecta que hay filtro activo %s', (_caso, activar) => {
		expect(estado.hayFiltroActivo()).toBe(false);

		activar();

		expect(estado.hayFiltroActivo()).toBe(true);
	});

	it('los espacios sueltos no cuentan como búsqueda', () => {
		estado.buscar('   ');

		expect(estado.hayFiltroActivo()).toBe(false);
		expect(estado.obtenerTareasVisibles()).toHaveLength(3);
	});

	it('el filtro no altera la lista real ni su orden', () => {
		estado.filtrarPorEstado('completadas');

		expect(estado.obtenerIdsEnOrden()).toEqual([1, 2, 3]);
	});

});

describe('mover tareas entre grupos', () => {

	/** Dos grupos con dos tareas cada uno, más una suelta, para poder mover en todas direcciones. */
	beforeEach(() => {
		estado.reemplazarTareas([
			{ id: 1, texto: 'a', completada: false },
			{ id: 2, texto: 'b', completada: false },
			{ id: 3, texto: 'c', completada: false },
			{ id: 4, texto: 'd', completada: false },
			{ id: 5, texto: 'suelta', completada: false }
		]);
		estado.reemplazarGrupos({
			grupos: [{ id: 10, nombre: 'Mañana' }, { id: 20, nombre: 'Casa' }],
			asignaciones: { 1: 10, 2: 10, 3: 20, 4: 20 }
		});
	});

	it('mueve una tarea dentro de su propio grupo', () => {
		expect(estado.moverTareaAGrupo(2, 10, 0)).toBe(true);

		expect(estado.obtenerTareasDeGrupo(10).map((t) => t.id)).toEqual([2, 1]);
	});

	it('mueve una tarea a otro grupo y la deja en la posición pedida', () => {
		estado.moverTareaAGrupo(1, 20, 1);

		expect(estado.obtenerGrupoDe(1)).toBe(20);
		expect(estado.obtenerTareasDeGrupo(20).map((t) => t.id)).toEqual([3, 1, 4]);
		expect(estado.obtenerTareasDeGrupo(10).map((t) => t.id)).toEqual([2]);
	});

	it('saca una tarea de su grupo al moverla a las sueltas', () => {
		estado.moverTareaAGrupo(1, null, 0);

		expect(estado.obtenerGrupoDe(1)).toBeNull();
		expect(estado.obtenerTareasDeGrupo(null).map((t) => t.id)).toEqual([1, 5]);
	});

	it('mete una tarea suelta en un grupo', () => {
		estado.moverTareaAGrupo(5, 10, 0);

		expect(estado.obtenerGrupoDe(5)).toBe(10);
		expect(estado.obtenerTareasDeGrupo(null)).toEqual([]);
	});

	/**
	 * El orden global pasa a ser la concatenación de las secciones tal y como se ven: cada grupo en
	 * su orden y al final las sueltas. Así lo que el usuario ve y lo que se persiste coinciden, y es
	 * lo que se manda a `PUT /tarea/orden`.
	 */
	it('recompone el orden global como la concatenación de las secciones', () => {
		estado.moverTareaAGrupo(5, 10, 1);

		expect(estado.obtenerIdsEnOrden()).toEqual([1, 5, 2, 3, 4]);
	});

	it('acota la posición en vez de dejar un hueco', () => {
		estado.moverTareaAGrupo(5, 10, 99);

		expect(estado.obtenerTareasDeGrupo(10).map((t) => t.id)).toEqual([1, 2, 5]);
	});

	it('avisa y no hace nada si la tarea no existe', () => {
		expect(estado.moverTareaAGrupo(99, 10, 0)).toBe(false);

		expect(estado.obtenerIdsEnOrden()).toEqual([1, 2, 3, 4, 5]);
	});

	it('no pierde ninguna tarea por el camino', () => {
		estado.moverTareaAGrupo(3, 10, 0);
		estado.moverTareaAGrupo(5, 20, 0);
		estado.moverTareaAGrupo(1, null, 0);

		expect(estado.obtenerIdsEnOrden().sort()).toEqual([1, 2, 3, 4, 5]);
	});

});

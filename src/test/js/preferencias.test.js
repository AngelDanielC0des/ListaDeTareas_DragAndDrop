import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as preferencias from '../../main/resources/static/js/preferencias.js';

/**
 * Pruebas del único módulo que toca `localStorage`.
 *
 * El caso que de verdad hay que cubrir es el que no se ve programando: en una ventana privada o con
 * los datos del sitio bloqueados, `localStorage` **lanza una excepción** en lugar de devolver vacío.
 * Si eso no estuviera contemplado, la aplicación no arrancaría en esas ventanas.
 */

/** Un `localStorage` de mentira, para no depender del que traiga el entorno. */
function almacenSimulado() {
	const datos = new Map();
	return {
		getItem: vi.fn((clave) => datos.get(clave) ?? null),
		setItem: vi.fn((clave, valor) => datos.set(clave, String(valor))),
		removeItem: vi.fn((clave) => datos.delete(clave)),
		datos
	};
}

/** Uno que lanza en todo, como el de una ventana privada con los datos bloqueados. */
function almacenQueLanza() {
	const romper = () => {
		throw new DOMException('acceso denegado', 'SecurityError');
	};
	return { getItem: vi.fn(romper), setItem: vi.fn(romper), removeItem: vi.fn(romper) };
}

/** @type {ReturnType<typeof almacenSimulado>} */
let almacen;

beforeEach(() => {
	almacen = almacenSimulado();
	vi.stubGlobal('localStorage', almacen);
});

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('leer el tema', () => {

	it('devuelve el tema guardado si es uno de los válidos', () => {
		almacen.datos.set('tareas.tema', 'oscuro');

		expect(preferencias.leerTema()).toBe('oscuro');
	});

	/**
	 * Sin nada guardado devuelve null, que no es un tema sino la ausencia de elección: es lo que
	 * hace que la aplicación siga al tema del sistema mientras el usuario no toque el interruptor.
	 */
	it('devuelve null cuando no hay nada guardado, para seguir al sistema', () => {
		expect(preferencias.leerTema()).toBeNull();
	});

	/** Si alguien edita a mano el almacenamiento, no se aplica una clase que el CSS no conoce. */
	it('ignora un valor que no es un tema conocido', () => {
		almacen.datos.set('tareas.tema', 'fucsia');

		expect(preferencias.leerTema()).toBeNull();
	});

	/** «Sistema» ya no es una opción elegible: solo hay claro y oscuro. */
	it('ya no acepta «sistema» como tema guardado', () => {
		almacen.datos.set('tareas.tema', 'sistema');

		expect(preferencias.leerTema()).toBeNull();
	});

});

describe('guardar el tema', () => {

	it('guarda «claro» y «oscuro»', () => {
		preferencias.guardarTema('oscuro');

		expect(almacen.setItem).toHaveBeenCalledWith('tareas.tema', 'oscuro');
	});

	/**
	 * Una vez elegido un tema, esa elección manda para siempre. Es la consecuencia asumida de tener
	 * dos opciones en vez de tres: no hay forma de volver al automático desde la interfaz.
	 */
	it('no acepta «sistema», que dejó de ser una opción elegible', () => {
		preferencias.guardarTema('sistema');

		expect(almacen.setItem).not.toHaveBeenCalled();
	});

	it('no escribe nada ante un tema que no existe', () => {
		preferencias.guardarTema('fucsia');

		expect(almacen.setItem).not.toHaveBeenCalled();
		expect(almacen.removeItem).not.toHaveBeenCalled();
	});

});

describe('cuando el navegador bloquea el almacenamiento', () => {

	beforeEach(() => {
		vi.stubGlobal('localStorage', almacenQueLanza());
	});

	it('leer no revienta: se sigue al tema del sistema', () => {
		expect(() => preferencias.leerTema()).not.toThrow();
		expect(preferencias.leerTema()).toBeNull();
	});

	it('guardar tampoco revienta: el tema durará lo que dure la pestaña', () => {
		expect(() => preferencias.guardarTema('oscuro')).not.toThrow();
	});

});

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

	it('cae en «sistema» cuando no hay nada guardado', () => {
		expect(preferencias.leerTema()).toBe('sistema');
	});

	/** Si alguien edita a mano el almacenamiento, no se aplica una clase que el CSS no conoce. */
	it('ignora un valor que no es un tema conocido', () => {
		almacen.datos.set('tareas.tema', 'fucsia');

		expect(preferencias.leerTema()).toBe('sistema');
	});

});

describe('guardar el tema', () => {

	it('guarda «claro» y «oscuro»', () => {
		preferencias.guardarTema('oscuro');

		expect(almacen.setItem).toHaveBeenCalledWith('tareas.tema', 'oscuro');
	});

	/**
	 * «Sistema» se guarda como ausencia de clave: si el usuario vuelve al valor por defecto, no debe
	 * quedar una preferencia marcando una elección que ya no existe.
	 */
	it('guarda «sistema» borrando la clave en vez de escribirla', () => {
		preferencias.guardarTema('sistema');

		expect(almacen.removeItem).toHaveBeenCalledWith('tareas.tema');
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

	it('leer no revienta: se usa la preferencia del sistema', () => {
		expect(() => preferencias.leerTema()).not.toThrow();
		expect(preferencias.leerTema()).toBe('sistema');
	});

	it('guardar tampoco revienta: el tema durará lo que dure la pestaña', () => {
		expect(() => preferencias.guardarTema('oscuro')).not.toThrow();
	});

});

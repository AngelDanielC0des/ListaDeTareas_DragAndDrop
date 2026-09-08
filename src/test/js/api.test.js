import { afterEach, describe, expect, it, vi } from 'vitest';

import * as api from '../../main/resources/static/js/api.js';
import { ErrorApi } from '../../main/resources/static/js/api.js';

/**
 * Pruebas de la única puerta hacia la API.
 *
 * Lo que importa aquí no son las rutas —que se ven leyendo— sino que todo fallo salga convertido en
 * un `ErrorApi`, que es la promesa que hace el módulo y de la que depende `app.js` para no tener un
 * `catch` distinto por llamada.
 */

/**
 * Simula una respuesta del servidor sin levantar nada.
 *
 * @param {number} estado código HTTP
 * @param {unknown} cuerpo lo que devuelve `json()`; `undefined` simula un cuerpo que no es JSON
 * @param {Record<string, string>} [cabeceras]
 */
function respuesta(estado, cuerpo, cabeceras = {}) {
	return {
		ok: estado >= 200 && estado < 300,
		status: estado,
		headers: { get: (/** @type {string} */ nombre) => cabeceras[nombre] ?? null },
		json: async () => {
			if (cuerpo === undefined) {
				throw new SyntaxError('no es JSON');
			}
			return cuerpo;
		}
	};
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('respuestas correctas', () => {

	it('devuelve el cuerpo ya interpretado', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(200, [{ id: 1, texto: 'una', completada: false }])));

		await expect(api.listarTareas()).resolves.toEqual([{ id: 1, texto: 'una', completada: false }]);
	});

	/** Un 204 no trae cuerpo: pedirle el JSON reventaría. */
	it('devuelve null ante un 204 sin intentar leer el cuerpo', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(204, undefined)));

		await expect(api.eliminarTarea(1)).resolves.toBeNull();
	});

	it('serializa el cuerpo y pone la cabecera de tipo de contenido', async () => {
		const peticion = vi.fn(async () => respuesta(200, {}));
		vi.stubGlobal('fetch', peticion);

		await api.crearTarea('Comprar pan');

		expect(peticion).toHaveBeenCalledOnce();
		const [ruta, opciones] = /** @type {[string, RequestInit & {headers: Record<string, string>}]} */
			(/** @type {unknown} */ (peticion.mock.calls[0]));
		expect(ruta).toBe('/tarea');
		expect(opciones.method).toBe('POST');
		expect(opciones.headers['Content-Type']).toBe('application/json');
		expect(JSON.parse(String(opciones.body))).toEqual({ texto: 'Comprar pan' });
	});

});

describe('errores del servidor', () => {

	it('convierte un ProblemDetail en un ErrorApi con su detalle', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(404, {
			title: 'Tarea no encontrada',
			detail: 'No existe la tarea 9'
		})));

		const fallo = await api.consultarConfiguracion().catch((e) => e);

		expect(fallo).toBeInstanceOf(ErrorApi);
		expect(fallo.estado).toBe(404);
		expect(fallo.mensajeUsuario).toBe('No existe la tarea 9');
	});

	/** Con errores de validación el mensaje útil es el del campo, no el genérico del cuerpo. */
	it('prefiere los mensajes por campo sobre el detalle general', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(400, {
			detail: 'Revisa los campos indicados',
			errores: { texto: 'El texto no puede estar vacío' }
		})));

		const fallo = await api.crearTarea('  ').catch((e) => e);

		expect(fallo.mensajeUsuario).toBe('El texto no puede estar vacío');
	});

	/** Un 500 del contenedor puede venir sin cuerpo JSON; eso no debe romper el parseo. */
	it('usa un mensaje de respaldo cuando el error no trae cuerpo interpretable', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(500, undefined)));

		const fallo = await api.listarTareas().catch((e) => e);

		expect(fallo).toBeInstanceOf(ErrorApi);
		expect(fallo.mensajeUsuario).toBe('Ha fallado el servidor. Vuelve a intentarlo.');
	});

	it('un estado sin mensaje propio cae en un texto genérico que dice el código', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => respuesta(418, {})));

		const fallo = await api.listarTareas().catch((e) => e);

		expect(fallo.mensajeUsuario).toBe('El servidor ha respondido 418.');
	});

});

describe('fallos de red', () => {

	/** Sin conexión `fetch` rechaza; también eso tiene que salir como ErrorApi. */
	it('convierte un fallo de red en ErrorApi conservando la causa', async () => {
		const causa = new TypeError('Failed to fetch');
		vi.stubGlobal('fetch', vi.fn(async () => {
			throw causa;
		}));

		const fallo = await api.listarTareas().catch((e) => e);

		expect(fallo).toBeInstanceOf(ErrorApi);
		expect(fallo.estado).toBe(0);
		expect(fallo.cause).toBe(causa);
	});

});

describe('peticiones al cerrar la pestaña', () => {

	it('van con keepalive y no propagan el fallo, que no habría quién lo viera', async () => {
		const peticion = vi.fn(
			(/** @type {string} */ _ruta, /** @type {RequestInit} */ _opciones) =>
				Promise.reject(new Error('la página se está cerrando')));
		vi.stubGlobal('fetch', peticion);

		expect(() => api.eliminarTareaAlSalir(1)).not.toThrow();

		expect(peticion.mock.calls[0][1].keepalive).toBe(true);
		// Se espera un ciclo para que, si el .catch faltara, saltase el rechazo sin gestionar.
		await new Promise((seguir) => setTimeout(seguir, 0));
	});

});

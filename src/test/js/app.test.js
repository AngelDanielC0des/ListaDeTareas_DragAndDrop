// @vitest-environment jsdom

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Pruebas del orquestador.
 *
 * Era el único módulo sin ninguna cobertura, y no era inocuo: ahí se escondió una llamada recursiva
 * en `repintar()` que dejaba la aplicación sin pintar nada, y ninguna de las otras pruebas la vio
 * porque todas llaman a `vista.pintarLista()` directamente y nunca pasan por `app.js`.
 *
 * Aquí se carga el `index.html` real, se simula la API y se importa `app.js`, que arranca solo al
 * cargarse. Lo que se comprueba es el cableado: que al arrancar se pinte, que los eventos lleguen a
 * la API y que un fallo del servidor deshaga el cambio en pantalla.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const INDEX = resolve(AQUI, '../../main/resources/static/index.html');

/** @type {import('../../main/resources/static/js/tipos.js').Tarea[]} */
const DOS_TAREAS = [
	{ id: 1, texto: 'Comprar pan', completada: false },
	{ id: 2, texto: 'Regar plantas', completada: true }
];

/**
 * La API simulada. Se declara con `vi.hoisted` porque `vi.mock` se eleva al principio del archivo y
 * necesita que esto ya exista cuando se ejecuta.
 *
 * Sin anotación de tipo a propósito: el objeto mezcla funciones simuladas con la clase `ErrorApi`,
 * que no es una de ellas, y un `Record` de mocks dejaría fuera precisamente a la clase.
 */
const api = vi.hoisted(() => ({
	// app.js importa la clase para distinguir un fallo de la API de cualquier otro error, así que
	// la simulación tiene que exportarla igual que el módulo real.
	ErrorApi: class ErrorApi extends Error {
		/**
		 * @param {number} estado
		 * @param {string} [titulo]
		 * @param {string} [detalle]
		 */
		constructor(estado, titulo, detalle) {
			super(detalle || titulo || `Error ${estado}`);
			this.estado = estado;
			this.mensajeUsuario = detalle || titulo || `Error ${estado}`;
		}
	},
	listarTareas: vi.fn(),
	consultarFondos: vi.fn(),
	consultarGrupos: vi.fn(),
	consultarConfiguracion: vi.fn(),
	crearTarea: vi.fn(),
	actualizarTarea: vi.fn(),
	cambiarCompletada: vi.fn(),
	eliminarTarea: vi.fn(),
	eliminarTareaAlSalir: vi.fn(),
	guardarTareaAlSalir: vi.fn(),
	reordenarAlSalir: vi.fn(),
	reordenarTareas: vi.fn(),
	consultarFondosDeTarea: vi.fn(),
	cambiarFondo: vi.fn(),
	crearGrupo: vi.fn(),
	renombrarGrupo: vi.fn(),
	eliminarGrupo: vi.fn(),
	cambiarGrupo: vi.fn()
}));

vi.mock('../../main/resources/static/js/api.js', () => api);

/** El cuerpo del HTML real, sin los <script>: los módulos los carga la prueba a mano. */
function cuerpoDeIndex() {
	const html = readFileSync(INDEX, 'utf8');
	const cuerpo = html.slice(html.indexOf('<body>') + '<body>'.length, html.indexOf('</body>'));
	return cuerpo.replace(/<script[\s\S]*?<\/script>/g, '');
}

/** Monta el DOM, prepara las respuestas y arranca la aplicación. */
async function arrancarLaAplicacion() {
	document.body.innerHTML = cuerpoDeIndex();

	api.listarTareas.mockResolvedValue(DOS_TAREAS);
	api.consultarFondos.mockResolvedValue({});
	api.consultarGrupos.mockResolvedValue({ grupos: [], asignaciones: {} });
	api.consultarConfiguracion.mockResolvedValue({ maxCaracteresTexto: 280 });

	vi.resetModules();
	await import('../../main/resources/static/js/app.js');

	// El arranque encadena dos await antes de pintar; esto deja que se resuelvan.
	await new Promise((seguir) => setTimeout(seguir, 0));
}

/** @param {string} selector */
function elemento(selector) {
	const encontrado = document.querySelector(selector);
	if (!(encontrado instanceof HTMLElement)) {
		throw new Error(`no está en el DOM: ${selector}`);
	}
	return encontrado;
}

beforeEach(() => {
	vi.clearAllMocks();
});

describe('arranque', () => {

	/**
	 * REGRESIÓN. `repintar()` llegó a llamarse a sí mismo en vez de a `vista.pintarLista()`, y la
	 * aplicación se quedaba en blanco con un desbordamiento de pila. Las 87 pruebas restantes
	 * seguían en verde porque ninguna pasa por `app.js`.
	 */
	it('pinta las tareas al arrancar', async () => {
		await arrancarLaAplicacion();

		expect(document.querySelectorAll('.tarea')).toHaveLength(2);
		expect(elemento('.tarea__texto').textContent).toBe('Comprar pan');
	});

	it('pide tareas, fondos y grupos en paralelo', async () => {
		await arrancarLaAplicacion();

		expect(api.listarTareas).toHaveBeenCalledOnce();
		expect(api.consultarFondos).toHaveBeenCalledOnce();
		expect(api.consultarGrupos).toHaveBeenCalledOnce();
	});

	it('pinta una sección por grupo', async () => {
		document.body.innerHTML = cuerpoDeIndex();
		api.listarTareas.mockResolvedValue(DOS_TAREAS);
		api.consultarFondos.mockResolvedValue({});
		api.consultarGrupos.mockResolvedValue({
			grupos: [{ id: 7, nombre: 'Mañana' }],
			asignaciones: { 1: 7 }
		});
		api.consultarConfiguracion.mockResolvedValue({ maxCaracteresTexto: 280 });

		vi.resetModules();
		await import('../../main/resources/static/js/app.js');
		await new Promise((seguir) => setTimeout(seguir, 0));

		const nombres = [...document.querySelectorAll('.seccion__nombre')].map((n) => n.textContent);
		expect(nombres).toEqual(['Mañana', 'Sin grupo']);
	});

	/** Que no arranque la configuración no debe impedir usar la aplicación. */
	it('sigue funcionando aunque falle la consulta de configuración', async () => {
		document.body.innerHTML = cuerpoDeIndex();
		api.listarTareas.mockResolvedValue(DOS_TAREAS);
		api.consultarFondos.mockResolvedValue({});
		api.consultarGrupos.mockResolvedValue({ grupos: [], asignaciones: {} });
		api.consultarConfiguracion.mockRejectedValue(new api.ErrorApi(0, 'Sin conexión'));

		vi.resetModules();
		await import('../../main/resources/static/js/app.js');
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(document.querySelectorAll('.tarea')).toHaveLength(2);
	});

});

describe('alta plegable', () => {

	it('el botón «+» despliega el formulario y pone el foco en el campo', async () => {
		await arrancarLaAplicacion();

		elemento('#boton-abrir-alta').click();

		expect(elemento('#formulario-nueva').hidden).toBe(false);
		expect(document.activeElement).toBe(elemento('#campo-texto'));
	});

	it('crear una tarea la manda a la API y la pinta', async () => {
		await arrancarLaAplicacion();
		api.crearTarea.mockResolvedValue({ id: 3, texto: 'Nueva', completada: false });

		elemento('#boton-abrir-alta').click();
		/** @type {HTMLInputElement} */ (elemento('#campo-texto')).value = 'Nueva';
		elemento('#formulario-nueva').dispatchEvent(new Event('submit', { cancelable: true }));
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(api.crearTarea).toHaveBeenCalledWith('Nueva');
		expect(document.querySelectorAll('.tarea')).toHaveLength(3);
	});

	it('no manda nada si el texto está en blanco', async () => {
		await arrancarLaAplicacion();

		elemento('#boton-abrir-alta').click();
		/** @type {HTMLInputElement} */ (elemento('#campo-texto')).value = '   ';
		elemento('#formulario-nueva').dispatchEvent(new Event('submit', { cancelable: true }));
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(api.crearTarea).not.toHaveBeenCalled();
		expect(elemento('#aviso-error').hidden).toBe(false);
	});

});

describe('completar una tarea', () => {

	it('manda el cambio y refleja la respuesta del servidor', async () => {
		await arrancarLaAplicacion();
		api.cambiarCompletada.mockResolvedValue({ id: 1, texto: 'Comprar pan', completada: true });

		const casilla = /** @type {HTMLInputElement} */ (elemento('.tarea__casilla'));
		casilla.checked = true;
		casilla.dispatchEvent(new Event('change', { bubbles: true }));
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(api.cambiarCompletada).toHaveBeenCalledWith(1, true);
		expect(elemento('.tarea').classList.contains('tarea--completada')).toBe(true);
	});

	/**
	 * La interfaz es optimista: pinta el cambio antes de que responda el servidor. Si el servidor lo
	 * rechaza hay que volver atrás, o la pantalla estaría mintiendo sobre lo que hay guardado.
	 */
	it('deshace el cambio en pantalla si el servidor lo rechaza', async () => {
		await arrancarLaAplicacion();
		api.cambiarCompletada.mockRejectedValue(new api.ErrorApi(500, 'Error', 'Ha fallado el servidor.'));

		const casilla = /** @type {HTMLInputElement} */ (elemento('.tarea__casilla'));
		casilla.checked = true;
		casilla.dispatchEvent(new Event('change', { bubbles: true }));
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(elemento('.tarea').classList.contains('tarea--completada')).toBe(false);
		expect(elemento('#aviso-error').hidden).toBe(false);
	});

});

describe('grupos', () => {

	it('crear un grupo lo manda a la API y recarga los grupos', async () => {
		await arrancarLaAplicacion();
		api.crearGrupo.mockResolvedValue({ id: 7, nombre: 'Mañana' });
		api.consultarGrupos.mockResolvedValue({ grupos: [{ id: 7, nombre: 'Mañana' }], asignaciones: {} });

		elemento('#boton-abrir-grupo').click();
		/** @type {HTMLInputElement} */ (elemento('#campo-grupo')).value = 'Mañana';
		elemento('#formulario-grupo').dispatchEvent(new Event('submit', { cancelable: true }));
		await new Promise((seguir) => setTimeout(seguir, 0));

		expect(api.crearGrupo).toHaveBeenCalledWith('Mañana');
		expect(elemento('.seccion__nombre').textContent).toBe('Mañana');
	});

});

describe('filtrar', () => {

	it('al filtrar solo se pintan las que pasan el filtro', async () => {
		await arrancarLaAplicacion();

		const completadas = /** @type {HTMLInputElement} */ (elemento('.filtros__radio[value="completadas"]'));
		completadas.checked = true;
		completadas.dispatchEvent(new Event('change', { bubbles: true }));

		expect(document.querySelectorAll('.tarea')).toHaveLength(1);
		expect(elemento('.tarea__texto').textContent).toBe('Regar plantas');
	});

	/** Con la lista parcial las posiciones que se ven no son las del estado. */
	it('avisa de que con filtro no se puede reordenar', async () => {
		await arrancarLaAplicacion();

		const pendientes = /** @type {HTMLInputElement} */ (elemento('.filtros__radio[value="pendientes"]'));
		pendientes.checked = true;
		pendientes.dispatchEvent(new Event('change', { bubbles: true }));

		expect(elemento('#aviso-arrastre').hidden).toBe(false);
	});

});

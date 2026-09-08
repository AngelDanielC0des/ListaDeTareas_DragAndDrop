/**
 * Preferencias del usuario guardadas en el navegador.
 *
 * Es el único módulo que toca `localStorage`, y guarda una sola cosa: el tema. Eso es deliberado.
 *
 * **Las tareas no se guardan aquí, ni deben.** El servidor es su fuente de verdad; duplicarlas en el
 * navegador crearía una segunda verdad que habría que mantener sincronizada, a cambio de nada. Lo
 * que sí encaja en `localStorage` es justo esto: una preferencia de este navegador que al servidor
 * no le importa.
 *
 * Todos los accesos van dentro de un `try`: en una ventana privada, o con los datos del sitio
 * bloqueados, `localStorage` **lanza una excepción** en lugar de devolver vacío. Si eso pasa, la
 * aplicación sigue funcionando; simplemente la preferencia dura lo que dure la pestaña.
 */

/**
 * Ojo: esta clave está también en el script en línea de `index.html`, que aplica el tema antes del
 * primer pintado. Si se cambia aquí hay que cambiarla allí, o el tema dejará de recordarse.
 */
const CLAVE_TEMA = 'tareas.tema';

/**
 * Que no haya nada guardado significa «sigue al sistema».
 *
 * No es un tema que se pueda elegir, es la ausencia de elección: por eso vale como valor por defecto
 * pero no aparece en el interruptor, que solo tiene claro y oscuro. La consecuencia, asumida a
 * propósito, es que una vez elegido uno ya no se vuelve al automático sin borrar los datos del sitio.
 */
const SIN_ELEGIR = null;

/** @typedef {'claro' | 'oscuro'} Tema */

/** @type {Tema[]} */
const TEMAS_VALIDOS = ['claro', 'oscuro'];

/**
 * Si una cadena cualquiera es uno de los temas que entendemos.
 *
 * Va aparte y devolviendo un predicado con tipo para que, dentro del `if`, el valor deje de ser «una
 * cadena o null» y pase a ser un tema de verdad. Es la comprobación que ya se hacía, solo que ahora
 * el comprobador de tipos también la entiende.
 *
 * @param {string | null} valor
 * @returns {valor is Tema}
 */
function esTemaValido(valor) {
	const resultado = valor !== null && TEMAS_VALIDOS.includes(/** @type {Tema} */ (valor));
	return resultado;
}

/**
 * El tema elegido, o `null` si nunca se ha elegido ninguno y toca seguir al del sistema.
 *
 * @returns {Tema | null}
 */
export function leerTema() {
	/** @type {Tema | null} */
	let resultado = SIN_ELEGIR;
	try {
		const guardado = localStorage.getItem(CLAVE_TEMA);
		if (esTemaValido(guardado)) {
			resultado = guardado;
		}
	}
	catch {
		// Ventana privada o datos del sitio bloqueados: se queda el valor por defecto.
	}
	return resultado;
}

/** @param {string} tema */
export function guardarTema(tema) {
	if (!esTemaValido(tema)) {
		return;
	}
	try {
		localStorage.setItem(CLAVE_TEMA, tema);
	}
	catch {
		// No poder guardar no es motivo para no aplicarlo: durará lo que dure la pestaña.
	}
}

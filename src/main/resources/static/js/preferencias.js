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

/** El valor por defecto: seguir lo que diga el sistema operativo. */
const TEMA_SISTEMA = 'sistema';

/** @typedef {'claro' | 'oscuro' | 'sistema'} Tema */

/** @type {Tema[]} */
const TEMAS_VALIDOS = ['claro', 'oscuro', TEMA_SISTEMA];

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

/** @returns {Tema} */
export function leerTema() {
	/** @type {Tema} */
	let resultado = TEMA_SISTEMA;
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
		// «Sistema» se guarda como ausencia de preferencia: así, si el usuario vuelve al valor por
		// defecto, no queda una clave marcando una elección que ya no existe.
		if (tema === TEMA_SISTEMA) {
			localStorage.removeItem(CLAVE_TEMA);
		}
		else {
			localStorage.setItem(CLAVE_TEMA, tema);
		}
	}
	catch {
		// No poder guardar no es motivo para no aplicarlo: durará lo que dure la pestaña.
	}
}

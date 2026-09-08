/**
 * Única puerta de salida hacia la API.
 *
 * Nadie más en la aplicación llama a fetch. Concentrar aquí las peticiones significa que hay un
 * solo sitio donde se interpreta el formato de error del servidor (ProblemDetail, RFC 9457) y un
 * solo tipo de error que propagar, en vez de un `.catch` distinto por cada llamada.
 */

const RUTA_BASE = '/tarea';

/**
 * El cuerpo de error que devuelve el servidor, en formato ProblemDetail (RFC 9457).
 *
 * @typedef {object} ProblemDetail
 * @property {string} [title]
 * @property {string} [detail]
 * @property {Record<string, string>} [errores] mapa campo -> mensaje en los errores de validación
 */

/**
 * Opciones de una petición. Es `RequestInit` con un añadido: `cuerpo` es el objeto que se va a
 * serializar, y `realizarPeticion` lo convierte en `body` y pone la cabecera correspondiente.
 *
 * @typedef {RequestInit & {cuerpo?: unknown}} OpcionesDePeticion
 */

/**
 * Mensajes de respaldo, en español, para cuando el servidor responde un error sin cuerpo que lo
 * explique. Solo se usan si falta el `detail`: cuando el servidor manda uno propio, gana el suyo.
 */
const MENSAJES_POR_ESTADO = {
	400: 'Los datos enviados no son válidos.',
	404: 'La tarea ya no existe. Recarga la página.',
	405: 'Esa operación no está permitida.',
	409: 'El cambio choca con el estado actual. Recarga la página.',
	415: 'El formato enviado no es válido.',
	500: 'Ha fallado el servidor. Vuelve a intentarlo.'
};

/** Error de la API con el ProblemDetail ya interpretado. */
export class ErrorApi extends Error {

	/**
	 * @param {number} estado código HTTP, o 0 si la petición ni siquiera salió
	 * @param {string} [titulo]
	 * @param {string} [detalle]
	 * @param {Record<string, string>} [errores]
	 * @param {unknown} [causa]
	 */
	constructor(estado, titulo, detalle, errores, causa) {
		// La causa se encadena en lugar de descartarse: sin ella, un fallo de red (CORS, DNS, un
		// certificado inválido) se pierde entero y en la consola solo queda «Sin conexión».
		super(detalle || titulo || `Error ${estado}`, { cause: causa });
		this.name = 'ErrorApi';
		this.estado = estado;
		this.titulo = titulo;
		this.detalle = detalle;
		/** Mapa campo -> mensaje que manda el servidor en los errores de validación. */
		this.errores = errores || {};
	}

	/** Mensaje listo para enseñar al usuario, prefiriendo el del campo concreto si lo hay. */
	get mensajeUsuario() {
		const porCampo = Object.values(this.errores);
		const resultado = porCampo.length > 0 ? porCampo.join('. ') : this.message;
		return resultado;
	}

}

/**
 * Realiza una petición y devuelve el cuerpo ya interpretado.
 *
 * Lanza ErrorApi tanto si el servidor responde con un código de error como si la red falla, para
 * que quien llama tenga un único tipo de fallo del que preocuparse.
 *
 * @param {string} ruta
 * @param {OpcionesDePeticion} [opciones]
 * @returns {Promise<any>} el cuerpo ya interpretado, o null si la respuesta no traía ninguno
 */
async function realizarPeticion(ruta, opciones = {}) {
	const { cuerpo, ...configuracion } = opciones;
	if (cuerpo !== undefined) {
		configuracion.headers = { 'Content-Type': 'application/json', ...(configuracion.headers || {}) };
		configuracion.body = JSON.stringify(cuerpo);
	}

	let respuesta;
	try {
		respuesta = await fetch(ruta, configuracion);
	}
	catch (causa) {
		throw new ErrorApi(0, 'Sin conexión', 'No se ha podido contactar con el servidor.', {}, causa);
	}

	if (!respuesta.ok) {
		throw await interpretarError(respuesta);
	}

	// 204 No Content no trae cuerpo: intentar leerlo como JSON reventaría.
	const vieneSinCuerpo = respuesta.status === 204 || respuesta.headers.get('Content-Length') === '0';

	let resultado = null;
	if (!vieneSinCuerpo) {
		resultado = await respuesta.json();
	}
	return resultado;
}

/**
 * @param {Response} respuesta
 * @returns {Promise<ErrorApi>}
 */
async function interpretarError(respuesta) {
	/** @type {ProblemDetail} */
	let problema = {};
	try {
		problema = await respuesta.json();
	}
	catch {
		// Un error sin cuerpo JSON (un 500 del contenedor, por ejemplo) no debe romper el parseo.
	}

	const resultado = new ErrorApi(
		respuesta.status,
		problema.title || 'Error',
		problema.detail || MENSAJES_POR_ESTADO[/** @type {keyof typeof MENSAJES_POR_ESTADO} */ (respuesta.status)]
			|| `El servidor ha respondido ${respuesta.status}.`,
		problema.errores);
	return resultado;
}

export function listarTareas() {
	const resultado = realizarPeticion(RUTA_BASE);
	return resultado;
}

/** @param {string} texto */
export function crearTarea(texto) {
	const resultado = realizarPeticion(RUTA_BASE, { method: 'POST', cuerpo: { texto } });
	return resultado;
}

/**
 * @param {number} id
 * @param {string} texto
 * @param {boolean} completada
 */
export function actualizarTarea(id, texto, completada) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}`, { method: 'PUT', cuerpo: { texto, completada } });
	return resultado;
}

/**
 * @param {number} id
 * @param {boolean} completada
 */
export function cambiarCompletada(id, completada) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}/completada`, { method: 'PATCH', cuerpo: { completada } });
	return resultado;
}

/** @param {number} id */
export function eliminarTarea(id) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}`, { method: 'DELETE' });
	return resultado;
}

/**
 * Manda una petición mientras la pestaña se cierra, sin esperar la respuesta.
 *
 * Las tres llamadas «al salir» comparten dos cosas que las distinguen del resto del módulo:
 * `keepalive`, que deja que la petición sobreviva a la descarga de la página, y que **no lanzan**.
 * No hay a quién enseñarle un error ni tiempo para reintentar; como mucho el cambio no llega.
 *
 * El `.catch` es obligatorio y no adorno: `fetch` no falla de forma síncrona, devuelve una promesa
 * que rechaza. Un `try/catch` alrededor no vería nada y un fallo de red dejaría una promesa
 * rechazada sin gestionar, que además despertaría al manejador de `unhandledrejection` para
 * intentar pintar un error en una página que ya se está yendo.
 *
 * @param {string} ruta
 * @param {OpcionesDePeticion} opciones
 */
function enviarAlSalir(ruta, opciones) {
	const configuracion = { ...opciones, keepalive: true };
	if (configuracion.cuerpo !== undefined) {
		configuracion.headers = { 'Content-Type': 'application/json' };
		configuracion.body = JSON.stringify(configuracion.cuerpo);
		delete configuracion.cuerpo;
	}
	fetch(ruta, configuracion).catch(() => {});
}

/** Borra una tarea cuando la pestaña se está cerrando. @param {number} id */
export function eliminarTareaAlSalir(id) {
	enviarAlSalir(`${RUTA_BASE}/${id}`, { method: 'DELETE' });
}

/**
 * Manda la edición que el temporizador del guardado automático no llegó a enviar.
 *
 * @param {number} id
 * @param {string} texto
 * @param {boolean} completada
 */
export function guardarTareaAlSalir(id, texto, completada) {
	enviarAlSalir(`${RUTA_BASE}/${id}`, { method: 'PUT', cuerpo: { texto, completada } });
}

/** Manda el orden que el temporizador no llegó a enviar. @param {number[]} ids */
export function reordenarAlSalir(ids) {
	enviarAlSalir(`${RUTA_BASE}/orden`, { method: 'PUT', cuerpo: { ids } });
}

/** @param {number[]} ids */
export function reordenarTareas(ids) {
	const resultado = realizarPeticion(`${RUTA_BASE}/orden`, { method: 'PUT', cuerpo: { ids } });
	return resultado;
}

/**
 * Qué fondo tiene cada tarea, en un solo objeto {id: fondo}.
 *
 * Va aparte de la lista de tareas porque una tarea son tres campos y así se queda: el fondo es
 * decoración y vive en su propio archivo del servidor.
 */
export function consultarFondos() {
	const resultado = realizarPeticion(`${RUTA_BASE}/fondo`);
	return resultado;
}

/**
 * Devuelve el mapa completo ya actualizado, para no tener que recomponerlo en el cliente.
 *
 * @param {number} id
 * @param {string} fondo
 */
export function cambiarFondo(id, fondo) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}/fondo`, { method: 'PUT', cuerpo: { fondo } });
	return resultado;
}

/* ------------------------------------------------------------------- Grupos */

/**
 * Los grupos y sus asignaciones, en una sola petición.
 *
 * Van juntos porque por separado no sirven de nada, y pedirlos en dos abriría una ventana en la que
 * el navegador tendría una mitad nueva y la otra vieja.
 *
 * @returns {Promise<import('./tipos.js').GruposConAsignaciones>}
 */
export function consultarGrupos() {
	const resultado = realizarPeticion(`${RUTA_BASE}/grupo`);
	return resultado;
}

/** @param {string} nombre */
export function crearGrupo(nombre) {
	const resultado = realizarPeticion(`${RUTA_BASE}/grupo`, { method: 'POST', cuerpo: { nombre } });
	return resultado;
}

/**
 * @param {number} idGrupo
 * @param {string} nombre
 */
export function renombrarGrupo(idGrupo, nombre) {
	const resultado = realizarPeticion(`${RUTA_BASE}/grupo/${idGrupo}`, { method: 'PUT', cuerpo: { nombre } });
	return resultado;
}

/** Borra el grupo. Sus tareas no se borran: se quedan sueltas. @param {number} idGrupo */
export function eliminarGrupo(idGrupo) {
	const resultado = realizarPeticion(`${RUTA_BASE}/grupo/${idGrupo}`, { method: 'DELETE' });
	return resultado;
}

/**
 * Mete una tarea en un grupo, o la deja suelta con `null`.
 *
 * @param {number} id
 * @param {number | null} grupo
 * @returns {Promise<import('./tipos.js').GruposConAsignaciones>}
 */
export function cambiarGrupo(id, grupo) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}/grupo`, { method: 'PUT', cuerpo: { grupo } });
	return resultado;
}

/** Límites que dicta el servidor, para no repetir el número máximo de caracteres en el navegador. */
export function consultarConfiguracion() {
	const resultado = realizarPeticion(`${RUTA_BASE}/configuracion`);
	return resultado;
}

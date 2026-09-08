/**
 * Única puerta de salida hacia la API.
 *
 * Nadie más en la aplicación llama a fetch. Concentrar aquí las peticiones significa que hay un
 * solo sitio donde se interpreta el formato de error del servidor (ProblemDetail, RFC 9457) y un
 * solo tipo de error que propagar, en vez de un `.catch` distinto por cada llamada.
 */

const RUTA_BASE = '/tarea';

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
 */
async function realizarPeticion(ruta, opciones = {}) {
	const configuracion = { ...opciones };
	if (configuracion.cuerpo !== undefined) {
		configuracion.headers = { 'Content-Type': 'application/json', ...(configuracion.headers || {}) };
		configuracion.body = JSON.stringify(configuracion.cuerpo);
		delete configuracion.cuerpo;
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
	if (respuesta.status === 204 || respuesta.headers.get('Content-Length') === '0') {
		return null;
	}

	const resultado = await respuesta.json();
	return resultado;
}

async function interpretarError(respuesta) {
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
		problema.detail || MENSAJES_POR_ESTADO[respuesta.status]
			|| `El servidor ha respondido ${respuesta.status}.`,
		problema.errores);
	return resultado;
}

export function listarTareas() {
	const resultado = realizarPeticion(RUTA_BASE);
	return resultado;
}

export function crearTarea(texto) {
	const resultado = realizarPeticion(RUTA_BASE, { method: 'POST', cuerpo: { texto } });
	return resultado;
}

export function actualizarTarea(id, texto, completada) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}`, { method: 'PUT', cuerpo: { texto, completada } });
	return resultado;
}

export function cambiarCompletada(id, completada) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}/completada`, { method: 'PATCH', cuerpo: { completada } });
	return resultado;
}

export function eliminarTarea(id) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}`, { method: 'DELETE' });
	return resultado;
}

/**
 * Borra una tarea cuando la pestaña se está cerrando.
 *
 * Va aparte del `eliminarTarea` normal por dos motivos: `keepalive` deja que la petición sobreviva a
 * la descarga de la página, y aquí no se puede esperar la respuesta ni enseñar un error, porque ya
 * no habrá nadie mirando. Por eso no lanza: como mucho, el borrado no llega y la tarea sigue ahí.
 */
export function eliminarTareaAlSalir(id) {
	fetch(`${RUTA_BASE}/${id}`, { method: 'DELETE', keepalive: true }).catch(() => {});
}

export function guardarTareaAlSalir(id, texto, completada) {
	fetch(`${RUTA_BASE}/${id}`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ texto, completada }),
		keepalive: true
	}).catch(() => {});
}

export function reordenarAlSalir(ids) {
	fetch(`${RUTA_BASE}/orden`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ ids }),
		keepalive: true
	}).catch(() => {});
}

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

/** Devuelve el mapa completo ya actualizado, para no tener que recomponerlo en el cliente. */
export function cambiarFondo(id, fondo) {
	const resultado = realizarPeticion(`${RUTA_BASE}/${id}/fondo`, { method: 'PUT', cuerpo: { fondo } });
	return resultado;
}

/** Límites que dicta el servidor, para no repetir el número máximo de caracteres en el navegador. */
export function consultarConfiguracion() {
	const resultado = realizarPeticion(`${RUTA_BASE}/configuracion`);
	return resultado;
}

/**
 * Estado del cliente: la única fuente de verdad.
 *
 * El DOM nunca se consulta para saber qué tareas hay o en qué orden están; el DOM es siempre una
 * proyección de este módulo. Esa es la regla que evita que la lógica se disperse por los
 * manejadores de eventos.
 */

/** @type {import('./tipos.js').Tarea[]} */
let tareas = [];

/** Ids de las tarjetas cuyo texto está desplegado. Es preferencia de vista, no se persiste. */
const tareasDesplegadas = new Set();

/**
 * Edición en curso, o null si no se está editando nada.
 *
 * Antes esto vivía en tres variables sueltas de `app.js`. Al ser huecos únicos para algo que es por
 * tarea, empezar a editar otra mientras había un guardado en vuelo corrompía la comparación que
 * decide si hace falta reenviar el texto. Aquí van juntos y se reinician a la vez.
 *
 * @type {{id: number, textoOriginal: string, textoGuardado: string} | null}
 */
let edicionEnCurso = null;

/**
 * Fondo de cada tarea, tal como lo manda el servidor: {id: 'ondas'}.
 *
 * Solo aparecen las tareas que tienen uno; el resto se pintan sin fondo. Se guarda aparte de las
 * tareas porque el servidor también lo tiene aparte: una tarea son tres campos.
 *
 * @type {import('./tipos.js').MapaDeFondos}
 */
let fondosPorTarea = {};

/**
 * El valor con el que el servidor representa «esta tarjeta no lleva fondo».
 *
 * Vive aquí y no en `vista.js` porque es un valor del protocolo, no de presentación: es lo que
 * viaja en el JSON y lo que devuelve `obtenerFondoDe` cuando una tarea no tiene ninguno.
 */
export const SIN_FONDO = 'ninguno';

/**
 * @param {number} id
 * @returns {string} el valor del fondo, o `SIN_FONDO` si esa tarea no tiene ninguno
 */
export function obtenerFondoDe(id) {
	const resultado = fondosPorTarea[id] ?? SIN_FONDO;
	return resultado;
}

/** @param {import('./tipos.js').MapaDeFondos} nuevos */
export function reemplazarFondos(nuevos) {
	fondosPorTarea = { ...nuevos };
}

/**
 * Devuelve la lista para LEERLA. No la copies aquí: `pintarLista()` y `actualizarResumen()` la piden
 * en cada repintado y en cada actualización de una sola tarjeta, así que copiar costaría O(n) por
 * pulsación de casilla sin que nadie lo aproveche.
 *
 * Quien necesite mutarla tiene `copiarTareas()`, que existe justo para eso.
 */
export function obtenerTareas() {
	const resultado = tareas;
	return resultado;
}

/** @param {import('./tipos.js').Tarea[]} nuevas */
export function reemplazarTareas(nuevas) {
	tareas = [...nuevas];
	// Una tarea que ya no existe no debe dejar rastro en las preferencias de vista.
	const idsVivos = new Set(tareas.map((tarea) => tarea.id));
	for (const id of [...tareasDesplegadas]) {
		if (!idsVivos.has(id)) {
			tareasDesplegadas.delete(id);
		}
	}
	if (edicionEnCurso !== null && !idsVivos.has(edicionEnCurso.id)) {
		edicionEnCurso = null;
	}
}

/** Copia superficial para poder deshacer un cambio optimista si el servidor lo rechaza. */
export function copiarTareas() {
	const resultado = tareas.map((tarea) => ({ ...tarea }));
	return resultado;
}

/**
 * @param {number} id
 * @returns {import('./tipos.js').Tarea | null}
 */
export function buscarTareaPorId(id) {
	const resultado = tareas.find((tarea) => tarea.id === id) ?? null;
	return resultado;
}

/**
 * @param {number} id
 * @returns {number} la posición, o -1 si no está
 */
export function buscarPosicionDeTarea(id) {
	const resultado = tareas.findIndex((tarea) => tarea.id === id);
	return resultado;
}

/** @param {import('./tipos.js').Tarea} tarea */
export function anadirTarea(tarea) {
	tareas.push(tarea);
}

/** @param {import('./tipos.js').Tarea} tareaActualizada */
export function reemplazarTarea(tareaActualizada) {
	const posicion = buscarPosicionDeTarea(tareaActualizada.id);
	if (posicion !== -1) {
		tareas[posicion] = tareaActualizada;
	}
}

/**
 * Reinserta una tarea en una posición concreta.
 *
 * La usa el «Deshacer» del borrado: reponerla al final la dejaría en un sitio que no es el suyo, y
 * lo que el usuario espera al deshacer es que todo quede exactamente como estaba.
 *
 * @param {number} posicion
 * @param {import('./tipos.js').Tarea} tarea
 */
export function insertarTareaEn(posicion, tarea) {
	const destino = Math.min(Math.max(posicion, 0), tareas.length);
	tareas.splice(destino, 0, tarea);
}

/** @param {number} id */
export function quitarTarea(id) {
	const posicion = buscarPosicionDeTarea(id);
	if (posicion !== -1) {
		tareas.splice(posicion, 1);
	}
	tareasDesplegadas.delete(id);
	if (edicionEnCurso?.id === id) {
		edicionEnCurso = null;
	}
}

/**
 * Mueve una tarea de una posición a otra. Es la operación que produce el nuevo orden.
 *
 * @param {number} desde
 * @param {number} hasta
 * @returns {boolean} si el movimiento era válido y se ha aplicado
 */
export function moverTareaDePosicion(desde, hasta) {
	const esMovimientoValido = desde !== hasta
		&& desde >= 0 && hasta >= 0
		&& desde < tareas.length && hasta < tareas.length;

	let resultado;
	if (esMovimientoValido) {
		const [movida] = tareas.splice(desde, 1);
		tareas.splice(hasta, 0, movida);
		resultado = true;
	}
	else {
		resultado = false;
	}
	return resultado;
}

export function obtenerIdsEnOrden() {
	const resultado = tareas.map((tarea) => tarea.id);
	return resultado;
}

/* --------------------------------------------------------------- Filtro */

/**
 * Qué tareas se están mostrando.
 *
 * Vive aquí y no en la vista porque decide **qué** hay que pintar, no cómo. Y no se persiste a
 * propósito: un filtro es algo de este momento, no una preferencia; que sobreviviera a una recarga
 * haría creer que se han perdido tareas.
 *
 * @type {{estado: 'todas' | 'pendientes' | 'completadas', busqueda: string}}
 */
let filtro = { estado: 'todas', busqueda: '' };

/** Si hay algún filtro activo. Es lo que decide si se puede reordenar. */
export function hayFiltroActivo() {
	const resultado = filtro.estado !== 'todas' || filtro.busqueda !== '';
	return resultado;
}

export function obtenerFiltro() {
	const resultado = { ...filtro };
	return resultado;
}

/** @param {'todas' | 'pendientes' | 'completadas'} estado */
export function filtrarPorEstado(estado) {
	filtro = { ...filtro, estado };
}

/** @param {string} busqueda */
export function buscar(busqueda) {
	filtro = { ...filtro, busqueda: busqueda.trim() };
}

/**
 * Las tareas que pasan el filtro, en el orden en que están.
 *
 * La comparación normaliza a minúsculas y **quita los acentos**: quien busca «anadir» espera
 * encontrar «añadir», y quien busca «cafe» espera encontrar «café». Sin esto, buscar en español
 * falla justo con las palabras más propias del idioma.
 */
export function obtenerTareasVisibles() {
	const buscado = normalizarParaBuscar(filtro.busqueda);
	const resultado = tareas.filter((tarea) => {
		const coincideElEstado = filtro.estado === 'todas'
			|| (filtro.estado === 'completadas') === tarea.completada;
		const coincideElTexto = buscado === '' || normalizarParaBuscar(tarea.texto).includes(buscado);
		return coincideElEstado && coincideElTexto;
	});
	return resultado;
}

/**
 * Deja un texto comparable: sin mayúsculas y sin tildes.
 *
 * `normalize('NFD')` separa cada letra de su acento y el reemplazo borra los acentos sueltos, que
 * son el rango U+0300-U+036F. Es la forma estándar de comparar sin depender del idioma.
 *
 * @param {string} texto
 */
function normalizarParaBuscar(texto) {
	const resultado = texto.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
	return resultado;
}

/* ------------------------------------------------------- Preferencias de vista */

/** @param {number} id */
export function estaDesplegada(id) {
	const resultado = tareasDesplegadas.has(id);
	return resultado;
}

/** @param {number} id */
export function alternarDesplegada(id) {
	if (tareasDesplegadas.has(id)) {
		tareasDesplegadas.delete(id);
	}
	else {
		tareasDesplegadas.add(id);
	}
}

/* ------------------------------------------------------------ Edición en curso */

export function obtenerIdEnEdicion() {
	const resultado = edicionEnCurso?.id ?? null;
	return resultado;
}

/**
 * @param {number} id
 * @param {string} textoActual
 */
export function empezarEdicionDe(id, textoActual) {
	edicionEnCurso = { id, textoOriginal: textoActual, textoGuardado: textoActual };
}

export function terminarEdicion() {
	edicionEnCurso = null;
}

/** Texto que tenía la tarea al empezar a editarla, para poder cancelar con Escape. */
export function obtenerTextoOriginalDeEdicion() {
	const resultado = edicionEnCurso?.textoOriginal ?? '';
	return resultado;
}

/** Último texto que confirmó el servidor, para no reenviar dos veces lo mismo. */
export function obtenerTextoGuardadoDeEdicion() {
	const resultado = edicionEnCurso?.textoGuardado ?? null;
	return resultado;
}

/** Solo surte efecto si la edición en curso es la de esa tarea; si no, se ignora. */
/**
 * @param {number} id
 * @param {string} texto
 */
export function registrarTextoGuardado(id, texto) {
	const edicion = edicionEnCurso;
	if (edicion?.id === id) {
		edicion.textoGuardado = texto;
	}
}

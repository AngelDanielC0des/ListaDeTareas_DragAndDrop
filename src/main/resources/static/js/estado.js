/**
 * Estado del cliente: la única fuente de verdad.
 *
 * El DOM nunca se consulta para saber qué tareas hay o en qué orden están; el DOM es siempre una
 * proyección de este módulo. Esa es la regla que evita que la lógica se disperse por los
 * manejadores de eventos.
 */

/** @type {{id: number, texto: string, completada: boolean}[]} */
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
 * @type {Record<number, string>}
 */
let fondosPorTarea = {};

export function obtenerFondoDe(id) {
	const resultado = fondosPorTarea[id] ?? 'ninguno';
	return resultado;
}

export function reemplazarFondos(nuevos) {
	fondosPorTarea = { ...nuevos };
}

export function obtenerTareas() {
	const resultado = tareas;
	return resultado;
}

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

export function buscarTareaPorId(id) {
	const resultado = tareas.find((tarea) => tarea.id === id) ?? null;
	return resultado;
}

export function buscarPosicionDeTarea(id) {
	const resultado = tareas.findIndex((tarea) => tarea.id === id);
	return resultado;
}

export function anadirTarea(tarea) {
	tareas.push(tarea);
}

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
 */
export function insertarTareaEn(posicion, tarea) {
	const destino = Math.min(Math.max(posicion, 0), tareas.length);
	tareas.splice(destino, 0, tarea);
}

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

/** Mueve una tarea de una posición a otra. Es la operación que produce el nuevo orden. */
export function moverTareaDePosicion(desde, hasta) {
	if (desde === hasta || desde < 0 || hasta < 0 || desde >= tareas.length || hasta >= tareas.length) {
		return false;
	}
	const [movida] = tareas.splice(desde, 1);
	tareas.splice(hasta, 0, movida);
	return true;
}

export function obtenerIdsEnOrden() {
	const resultado = tareas.map((tarea) => tarea.id);
	return resultado;
}

/* ------------------------------------------------------- Preferencias de vista */

export function estaDesplegada(id) {
	const resultado = tareasDesplegadas.has(id);
	return resultado;
}

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
export function registrarTextoGuardado(id, texto) {
	if (edicionEnCurso?.id === id) {
		edicionEnCurso.textoGuardado = texto;
	}
}

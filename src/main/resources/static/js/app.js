/**
 * Toda la lógica del navegador: estado, pintado y manejadores de eventos.
 *
 * En esta versión va todo en un archivo a propósito, porque cabe de sobra y se sigue de un vistazo.
 * Lo único que se separa es `api.js`, para que exista un único sitio donde se llama a fetch y se
 * interpretan los errores del servidor.
 *
 * La regla que mantiene esto ordenado: **el DOM nunca se consulta para saber qué hay**. La lista de
 * tareas vive en la variable `tareas` y la pantalla es siempre una proyección suya. Tras cada
 * cambio se repinta la lista entera; con decenas de tareas es instantáneo y evita toda una clase de
 * errores por tener el estado en dos sitios.
 */

import * as api from './api.js';
import { ErrorApi } from './api.js';

/** @type {{id: number, texto: string, completada: boolean}[]} */
let tareas = [];

/** Id de la tarea que se está editando, o null. Solo puede haber una a la vez. */
let idEnEdicion = null;

const elementos = {
	formulario: document.getElementById('formulario-nueva'),
	campoTexto: document.getElementById('campo-texto'),
	botonAnadir: document.getElementById('boton-anadir'),
	lista: document.getElementById('lista'),
	listaVacia: document.getElementById('lista-vacia'),
	plantilla: document.getElementById('plantilla-tarea'),
	avisoError: document.getElementById('aviso-error'),
	resumen: document.getElementById('resumen'),
	anuncios: document.getElementById('anuncios')
};

iniciarAplicacion();

async function iniciarAplicacion() {
	registrarEventos();
	await cargarTareas();
}

function registrarEventos() {
	elementos.formulario.addEventListener('submit', (evento) => {
		evento.preventDefault();
		crearTarea(elementos.campoTexto.value);
	});

	// Manejadores delegados en el contenedor: uno por tipo de evento, no uno por tarjeta. Así
	// añadir o quitar tareas no obliga a montar ni desmontar listeners.
	elementos.lista.addEventListener('click', alPulsarEnLista);
	elementos.lista.addEventListener('change', alCambiarCasilla);

	// Red de seguridad: ningún fallo asíncrono debe quedarse mudo.
	globalThis.addEventListener('unhandledrejection', (evento) => mostrarError(evento.reason));
}

/* ------------------------------------------------------------------ Acciones */

async function cargarTareas() {
	try {
		tareas = await api.listarTareas();
		pintarLista();
	}
	catch (error) {
		mostrarError(error);
	}
}

async function crearTarea(texto) {
	const textoLimpio = texto.trim();
	if (textoLimpio.length === 0) {
		mostrarMensajeDeError('Escribe algo antes de añadir la tarea.');
		return;
	}

	// El botón se desactiva mientras va la petición: sin esto, pulsar Enter dos veces seguidas
	// manda dos POST y crea la tarea por duplicado.
	elementos.botonAnadir.disabled = true;
	try {
		const creada = await api.crearTarea(textoLimpio);
		tareas = [...tareas, creada];
		elementos.campoTexto.value = '';
		pintarLista();
		limpiarError();
		anunciar(`Tarea añadida: ${creada.texto}`);
	}
	catch (error) {
		mostrarError(error);
	}
	finally {
		elementos.botonAnadir.disabled = false;
		elementos.campoTexto.focus();
	}
}

/**
 * Guarda texto y estado de una tarea.
 *
 * Es la única función que llama al PUT, y la usan tanto la casilla de completada como el botón de
 * guardar la edición. Así solo hay una ruta de código que actualiza una tarea.
 */
async function guardarTarea(id, texto, completada) {
	const textoLimpio = texto.trim();
	if (textoLimpio.length === 0) {
		mostrarMensajeDeError('El texto de la tarea no puede quedar vacío.');
		return;
	}

	try {
		const actualizada = await api.actualizarTarea(id, textoLimpio, completada);
		tareas = tareas.map((tarea) => (tarea.id === id ? actualizada : tarea));
		idEnEdicion = null;
		pintarLista();
		limpiarError();
		anunciar(actualizada.completada ? 'Tarea completada.' : 'Tarea guardada.');
	}
	catch (error) {
		mostrarError(error);

		// Si el fallo ocurre mientras se edita ESTA tarea, repintar la reconstruiría con el texto
		// del estado —que no incluye lo que el usuario acaba de teclear— y le borraría el trabajo.
		// En los demás casos sí hay que repintar: la pantalla puede haberse quedado enseñando un
		// cambio que el servidor ha rechazado, como la casilla marcada.
		if (idEnEdicion !== id) {
			pintarLista();
		}
	}
}

async function eliminarTarea(id) {
	const tarea = buscarTarea(id);
	if (!tarea || !globalThis.confirm(`¿Borrar la tarea «${tarea.texto}»?`)) {
		return;
	}

	try {
		await api.eliminarTarea(id);
		tareas = tareas.filter((otra) => otra.id !== id);
		if (idEnEdicion === id) {
			idEnEdicion = null;
		}
		pintarLista();
		limpiarError();
		anunciar('Tarea borrada.');
	}
	catch (error) {
		mostrarError(error);
	}
}

/* -------------------------------------------------------------- Manejadores */

function alPulsarEnLista(evento) {
	const boton = evento.target.closest('button');
	const tarjeta = boton?.closest('.tarea');
	if (!boton || !tarjeta) {
		return;
	}

	const id = Number(tarjeta.dataset.id);
	const tarea = buscarTarea(id);
	if (!tarea) {
		return;
	}

	switch (boton.dataset.accion) {
		case 'editar':
			idEnEdicion = id;
			pintarLista();
			break;
		case 'cancelar':
			idEnEdicion = null;
			pintarLista();
			break;
		case 'guardar':
			guardarTarea(id, tarjeta.querySelector('.tarea__editor').value, tarea.completada);
			break;
		case 'eliminar':
			eliminarTarea(id);
			break;
		default:
			break;
	}
}

function alCambiarCasilla(evento) {
	const casilla = evento.target.closest('.tarea__casilla');
	if (!casilla) {
		return;
	}
	const id = Number(casilla.closest('.tarea').dataset.id);
	const tarea = buscarTarea(id);
	if (tarea) {
		guardarTarea(id, tarea.texto, casilla.checked);
	}
}

/* ------------------------------------------------------------------ Pintado */

/**
 * Reconstruye la lista entera a partir del estado.
 *
 * Se clona la <template> del index y se rellena con textContent: el texto que escribe el usuario
 * nunca puede interpretarse como marcado (XSS).
 */
function pintarLista() {
	const fragmento = document.createDocumentFragment();
	for (const tarea of tareas) {
		fragmento.appendChild(construirTarjeta(tarea));
	}

	elementos.lista.replaceChildren(fragmento);
	elementos.listaVacia.hidden = tareas.length > 0;
	actualizarResumen();
	enfocarEditorSiSeEstaEditando();
}

function construirTarjeta(tarea) {
	const enEdicion = (idEnEdicion === tarea.id);
	const tarjeta = elementos.plantilla.content.firstElementChild.cloneNode(true);

	tarjeta.dataset.id = String(tarea.id);
	tarjeta.classList.toggle('tarea--completada', tarea.completada);

	const casilla = tarjeta.querySelector('.tarea__casilla');
	const etiqueta = tarjeta.querySelector('label');
	casilla.checked = tarea.completada;
	casilla.id = `casilla-${tarea.id}`;
	etiqueta.htmlFor = casilla.id;
	etiqueta.textContent = tarea.completada ? 'Marcar como pendiente' : 'Marcar como completada';

	const parrafo = tarjeta.querySelector('.tarea__texto');
	parrafo.textContent = tarea.texto;
	parrafo.hidden = enEdicion;

	const editor = tarjeta.querySelector('.tarea__editor');
	editor.value = tarea.texto;
	editor.setAttribute('aria-label', 'Editar el texto de la tarea');
	editor.hidden = !enEdicion;

	// Se enseñan los botones normales o los de edición, nunca los dos a la vez.
	const [accionesNormales, accionesDeEdicion] = tarjeta.querySelectorAll('.tarea__acciones');
	accionesNormales.hidden = enEdicion;
	accionesDeEdicion.hidden = !enEdicion;

	return tarjeta;
}

function enfocarEditorSiSeEstaEditando() {
	if (idEnEdicion === null) {
		return;
	}
	const editor = elementos.lista.querySelector(`[data-id="${idEnEdicion}"] .tarea__editor`);
	if (editor) {
		editor.focus();
		editor.setSelectionRange(editor.value.length, editor.value.length);
	}
}

function actualizarResumen() {
	const completadas = tareas.filter((tarea) => tarea.completada).length;
	elementos.resumen.textContent = (tareas.length === 0) ? 'Sin tareas.'
		: `${completadas} de ${tareas.length} completadas.`;
}

/* ---------------------------------------------------- Auxiliares y avisos */

function buscarTarea(id) {
	const resultado = tareas.find((tarea) => tarea.id === id) ?? null;
	return resultado;
}

/**
 * Mensaje solo para lectores de pantalla.
 *
 * Añadir, completar o borrar una tarea se ve, pero no se «oye»: sin esta región quien navegue con
 * un lector de pantalla no se entera de que la acción ha surtido efecto.
 */
function anunciar(mensaje) {
	elementos.anuncios.textContent = mensaje;
}

/** Único punto donde un error se convierte en algo que el usuario puede leer. */
function mostrarError(error) {
	const mensaje = (error instanceof ErrorApi)
		? error.mensajeUsuario
		: 'Ha ocurrido un error inesperado. Vuelve a intentarlo.';

	mostrarMensajeDeError(mensaje);
	console.error(error);
}

function mostrarMensajeDeError(mensaje) {
	elementos.avisoError.textContent = mensaje;
	elementos.avisoError.hidden = false;
}

function limpiarError() {
	elementos.avisoError.textContent = '';
	elementos.avisoError.hidden = true;
}

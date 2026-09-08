/**
 * Orquestador: conecta eventos -> estado -> API -> vista.
 *
 * Es el único módulo que conoce a los demás. Los manejadores están delegados en el contenedor de
 * la lista (uno por tipo de evento, no uno por tarjeta), así que añadir o quitar tarjetas no
 * implica montar ni desmontar listeners.
 *
 * Todas las mutaciones siguen el mismo guion: guardar un respaldo, aplicar el cambio en local,
 * refrescar lo mínimo de la interfaz, llamar a la API y, si falla, restaurar el respaldo y avisar.
 * Eso da una interfaz que responde al instante sin mentir sobre lo que hay guardado.
 */

import * as api from './api.js';
import { ErrorApi } from './api.js';
import * as estado from './estado.js';
import * as arrastre from './arrastre.js';
import * as preferencias from './preferencias.js';
import * as vista from './vista.js';

/** Espera tras la última tecla antes de guardar una edición. */
const RETARDO_GUARDADO_DE_TEXTO = 500;

/**
 * Espera antes de mandar el nuevo orden.
 *
 * Sin esto, mantener pulsado Ctrl+Flecha lanzaba una petición por posición. Agrupando la ráfaga
 * sale una sola, y de paso se reduce la ventana en la que dos respuestas pueden cruzarse.
 */
const RETARDO_GUARDADO_DE_ORDEN = 300;

/** Margen para arrepentirse de un borrado antes de que salga la petición. */
const ESPERA_PARA_DESHACER = 6000;

const formulario = document.getElementById('formulario-nueva');

let temporizadorDeTexto = null;

let temporizadorDeOrden = null;

/** Estado de la lista antes de la primera reordenación de una ráfaga, por si hay que deshacerla. */
let respaldoAntesDeReordenar = null;

/**
 * Contador de peticiones por operación, para descartar las respuestas que llegan tarde.
 *
 * Dos peticiones a la misma operación pueden resolverse en orden distinto al que se lanzaron. Sin
 * este testigo, la respuesta de la más antigua machacaba el estado con datos ya caducos: al
 * reordenar deprisa, el servidor devolvía el orden anterior y el estado dejaba de coincidir con lo
 * que se veía en pantalla.
 */
const ultimaSecuenciaPorOperacion = new Map();

/**
 * Borrado a la espera de confirmarse, o null.
 *
 * Guarda la posición además de la tarea: reponerla al final al deshacer la dejaría en un sitio que
 * no es el suyo.
 *
 * @type {{tarea: object, posicion: number, temporizador: number} | null}
 */
let borradoPendiente = null;

iniciarAplicacion();

async function iniciarAplicacion() {
	vista.aplicarTema(preferencias.leerTema());
	registrarEventos();
	vista.vigilarCambiosDeAncho();
	arrastre.iniciarReordenacion({ alMover: moverTarea });

	await cargarConfiguracion();
	await cargarTareas();
}

/** El límite de caracteres lo dicta el servidor: así no está escrito a mano ni en el HTML ni aquí. */
async function cargarConfiguracion() {
	try {
		const configuracion = await api.consultarConfiguracion();
		vista.configurarLimiteDeTexto(configuracion.maxCaracteresTexto);
	}
	catch (error) {
		// No es motivo para no arrancar: el servidor sigue validando la longitud de todas formas.
		console.warn('No se pudo leer la configuración del servidor; se sigue sin límite en el navegador', error);
	}
}

/**
 * Pide las tareas y sus fondos a la vez.
 *
 * Van en dos peticiones porque son dos recursos distintos en el servidor —una tarea son tres campos
 * y el fondo es decoración aparte—, pero se lanzan en paralelo y se pinta una sola vez.
 */
async function cargarTareas() {
	try {
		const [tareas, fondos] = await Promise.all([api.listarTareas(), api.consultarFondos()]);
		estado.reemplazarTareas(tareas);
		estado.reemplazarFondos(fondos);
		vista.pintarLista();
	}
	catch (error) {
		manejarError(error);
	}
}

function registrarEventos() {
	formulario.addEventListener('submit', (evento) => {
		evento.preventDefault();
		anadirTarea(vista.campoTexto.value);
	});

	vista.campoTexto.addEventListener('input', () => vista.actualizarContador());

	vista.lista.addEventListener('click', alPulsarEnLista);
	vista.lista.addEventListener('change', alCambiarCasilla);
	vista.lista.addEventListener('input', alEscribirEnEditor);
	vista.lista.addEventListener('keydown', alTeclearEnEditor);
	// El foco no burbujea, pero focusout sí, que es lo que permite delegarlo en el contenedor.
	vista.lista.addEventListener('focusout', alSalirDelEditor);

	vista.alCambiarElTema((tema) => {
		preferencias.guardarTema(tema);
		vista.aplicarTema(tema);
	});

	document.addEventListener('keydown', alPulsarAtajo);

	// Si la pestaña se cierra con un borrado a medias, se manda igualmente: si no, la tarea
	// reaparecería al volver y el borrado parecería no haber funcionado.
	globalThis.addEventListener('pagehide', alCerrarLaPagina);

	// Red de seguridad: ningún fallo asíncrono debe quedarse mudo.
	globalThis.addEventListener('unhandledrejection', (evento) => manejarError(evento.reason));
}

/* ------------------------------------------------------------- Manejadores */

/**
 * Reparte los clics de la lista según el botón que se haya pulsado.
 *
 * Los casos son excluyentes —un botón es uno de los cuatro— así que se escriben como un `switch` y
 * no como cuatro `if` sueltos con su `return`: de un vistazo se ve que forman una sola decisión y
 * que están todos contemplados.
 */
function alPulsarEnLista(evento) {
	const boton = evento.target.closest('button');
	const id = idDeLaTarjetaDe(boton);
	if (id === null) {
		return;
	}

	if (boton.classList.contains('tarea__boton-desplegar')) {
		estado.alternarDesplegada(id);
		vista.actualizarTarjeta(id);
	}
	else {
		switch (boton.dataset.accion) {
			case 'fondo':
				elegirFondo(id);
				break;
			case 'eliminar':
				eliminarTarea(id);
				break;
			case 'editar':
				alternarEdicion(id);
				break;
			default:
				// Un botón de la tarjeta sin acción reconocida: no hay nada que hacer.
				break;
		}
	}
}

/** Entrar o salir de la edición, según si esa tarea ya se estaba editando. */
function alternarEdicion(id) {
	if (estado.obtenerIdEnEdicion() === id) {
		terminarEdicion(id, { cancelar: false });
	}
	else {
		empezarEdicion(id);
	}
}

/**
 * El id de la tarea a la que pertenece un elemento de la lista, o `null` si no está dentro de una.
 *
 * Recoge el `Number(...closest('.tarea').dataset.id)` que estaba repetido en los cinco manejadores,
 * y de paso les quita la comprobación de que el elemento exista, que cada uno hacía a su manera.
 */
function idDeLaTarjetaDe(elemento) {
	const tarjeta = elemento?.closest('.tarea');

	let resultado = null;
	if (tarjeta) {
		resultado = Number(tarjeta.dataset.id);
	}
	return resultado;
}

function alCambiarCasilla(evento) {
	const casilla = evento.target.closest('.tarea__casilla');
	if (!casilla) {
		return;
	}
	cambiarCompletada(idDeLaTarjetaDe(casilla), casilla.checked);
}

function alEscribirEnEditor(evento) {
	const editor = evento.target.closest('.tarea__editor');
	if (!editor) {
		return;
	}
	const id = idDeLaTarjetaDe(editor);
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}

	// Se actualiza el estado sin refrescar la tarjeta: hacerlo movería el cursor a cada tecla.
	estado.reemplazarTarea({ ...tarea, texto: editor.value });

	clearTimeout(temporizadorDeTexto);
	temporizadorDeTexto = setTimeout(() => guardarTexto(id), RETARDO_GUARDADO_DE_TEXTO);
}

function alTeclearEnEditor(evento) {
	const editor = evento.target.closest('.tarea__editor');
	if (!editor) {
		return;
	}
	const id = idDeLaTarjetaDe(editor);

	if (evento.key === 'Escape') {
		evento.preventDefault();
		terminarEdicion(id, { cancelar: true });
		return;
	}
	// Enter confirma; Mayús+Enter deja escribir varias líneas.
	if (evento.key === 'Enter' && !evento.shiftKey) {
		evento.preventDefault();
		terminarEdicion(id, { cancelar: false });
	}
}

function alSalirDelEditor(evento) {
	const editor = evento.target.closest('.tarea__editor');
	if (!editor) {
		return;
	}
	terminarEdicion(idDeLaTarjetaDe(editor), { cancelar: false });
}

/* ----------------------------------------------------------------- Acciones */

async function anadirTarea(texto) {
	const textoLimpio = texto.trim();
	if (textoLimpio.length === 0) {
		vista.mostrarError('Escribe algo antes de añadir la tarea.');
		vista.campoTexto.focus();
		return;
	}

	// Aquí no hay interfaz optimista: el id lo asigna el servidor y pintar una tarjeta sin id
	// dejaría un elemento con el que no se puede interactuar hasta que llegue la respuesta.
	vista.bloquearAlta(true);
	try {
		const creada = await api.crearTarea(textoLimpio);
		estado.anadirTarea(creada);
		vista.campoTexto.value = '';
		vista.actualizarContador();
		vista.pintarLista();
		vista.limpiarError();
		vista.anunciar(`Tarea añadida: ${creada.texto}`);
	}
	catch (error) {
		manejarError(error);
	}
	finally {
		vista.bloquearAlta(false);
		vista.campoTexto.focus();
	}
}

async function cambiarCompletada(id, completada) {
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}

	const respaldo = estado.copiarTareas();
	estado.reemplazarTarea({ ...tarea, completada });
	vista.actualizarTarjeta(id);

	const secuencia = anotarNuevaPeticion(`completada:${id}`);
	try {
		const actualizada = await api.cambiarCompletada(id, completada);
		if (!esLaPeticionMasReciente(`completada:${id}`, secuencia)) {
			return;
		}
		estado.reemplazarTarea(actualizada);
		vista.actualizarTarjeta(id);
		vista.limpiarError();
	}
	catch (error) {
		deshacerCambioOptimista(respaldo, error);
	}
}

/**
 * Quita la tarea de la vista y da unos segundos para arrepentirse antes de borrarla de verdad.
 *
 * Sustituye al `confirm()` del navegador, que bloquea y no se puede estilar. Y es mejor que
 * confirmar: en vez de preguntar antes, deja deshacer después.
 *
 * **La petición se retrasa a propósito.** Mandar el DELETE de inmediato y «deshacer» creando la
 * tarea otra vez no serviría: la nueva tendría un id distinto y aparecería al final de la lista, no
 * en su sitio. Esperando, la tarea conserva su id y su posición.
 */
function eliminarTarea(id) {
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}

	// Solo se sostiene un borrado a la vez: si había otro esperando, se confirma ya. Apilar avisos
	// complicaría la interfaz para un caso que casi no ocurre.
	confirmarBorradoPendiente();

	const posicion = estado.buscarPosicionDeTarea(id);
	estado.quitarTarea(id);
	vista.pintarLista();
	vista.anunciar(`Tarea borrada: ${tarea.texto}. Puedes deshacerlo.`);

	// Al desaparecer la tarjeta, el foco se caería al body y quien navega con teclado perdería el
	// sitio. Se lleva al botón de deshacer, que además es lo siguiente que puede querer pulsar.
	borradoPendiente = {
		tarea,
		posicion,
		temporizador: setTimeout(confirmarBorradoPendiente, ESPERA_PARA_DESHACER)
	};
	vista.mostrarDeshacer(tarea.texto, deshacerBorrado);
	vista.enfocarDeshacer();
}

function deshacerBorrado() {
	if (borradoPendiente === null) {
		return;
	}
	clearTimeout(borradoPendiente.temporizador);

	const borrada = borradoPendiente.tarea;
	estado.insertarTareaEn(borradoPendiente.posicion, borrada);
	borradoPendiente = null;

	vista.ocultarDeshacer();
	vista.pintarLista();
	vista.limpiarError();
	vista.anunciar('Borrado deshecho.');
	vista.enfocarTarjeta(borrada.id);
}

/** Manda el DELETE que estaba esperando. Si falla, la tarea vuelve a su sitio y se avisa. */
async function confirmarBorradoPendiente() {
	if (borradoPendiente === null) {
		return;
	}
	const { tarea, posicion, temporizador } = borradoPendiente;
	clearTimeout(temporizador);
	borradoPendiente = null;
	vista.ocultarDeshacer();

	try {
		await api.eliminarTarea(tarea.id);
		vista.limpiarError();
	}
	catch (error) {
		estado.insertarTareaEn(posicion, tarea);
		vista.pintarLista();
		manejarError(error);
	}
}

/**
 * Confirma el borrado al cerrar la pestaña.
 *
 * `keepalive` permite que la petición sobreviva a la descarga de la página; sin él el navegador la
 * cancelaría y la tarea reaparecería al volver.
 */
function alCerrarLaPagina() {
	vaciarGuardadoDeTextoPendiente();
	vaciarGuardadoDeOrdenPendiente();
	confirmarBorradoAlSalir();
}

/**
 * Manda ya la edición que estaba esperando al temporizador.
 *
 * Sin esto, cerrar la pestaña dentro del medio segundo siguiente a la última tecla perdía lo escrito
 * en silencio, que es justo lo contrario de lo que promete un guardado automático.
 *
 * Qué tarea es se le pregunta al estado, no al DOM: `estado.js` es la fuente de verdad, y buscar el
 * `.tarea__editor:focus` fallaría si el foco ya se ha ido al empezar a descargarse la página.
 */
function vaciarGuardadoDeTextoPendiente() {
	if (temporizadorDeTexto === null) {
		return;
	}
	clearTimeout(temporizadorDeTexto);
	temporizadorDeTexto = null;

	const id = estado.obtenerIdEnEdicion();
	const tarea = (id === null) ? null : estado.buscarTareaPorId(id);
	if (tarea === null) {
		return;
	}

	const textoEnviado = tarea.texto.trim();
	const haCambiado = textoEnviado.length > 0 && textoEnviado !== estado.obtenerTextoGuardadoDeEdicion();
	if (haCambiado) {
		api.guardarTareaAlSalir(id, textoEnviado, tarea.completada);
	}
}

/** Lo mismo para el orden: una ráfaga de Ctrl+Flecha sin confirmar no debe perderse al cerrar. */
function vaciarGuardadoDeOrdenPendiente() {
	if (temporizadorDeOrden === null) {
		return;
	}
	clearTimeout(temporizadorDeOrden);
	temporizadorDeOrden = null;
	respaldoAntesDeReordenar = null;

	api.reordenarAlSalir(estado.obtenerIdsEnOrden());
}

function confirmarBorradoAlSalir() {
	if (borradoPendiente === null) {
		return;
	}
	const { tarea, temporizador } = borradoPendiente;
	clearTimeout(temporizador);
	borradoPendiente = null;

	api.eliminarTareaAlSalir(tarea.id);
}

/* ------------------------------------------------------- Fondo de la tarjeta */

/**
 * Abre el selector de fondo y guarda la elección.
 *
 * El servidor devuelve el mapa de fondos ya actualizado, así que no hace falta recomponerlo aquí ni
 * dar por hecho que la escritura ha ido bien: se pinta lo que el servidor dice que hay.
 */
function elegirFondo(id) {
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}

	vista.abrirSelectorDeFondo(tarea, estado.obtenerFondoDe(id), async (fondoElegido) => {
		vista.cerrarSelectorDeFondo();
		try {
			const fondos = await api.cambiarFondo(id, fondoElegido);
			estado.reemplazarFondos(fondos);
			vista.actualizarTarjeta(id);
			vista.limpiarError();
			vista.anunciar(`Fondo cambiado en la tarea ${tarea.texto}.`);
		}
		catch (error) {
			manejarError(error);
		}
	});
}

/**
 * Único punto de reordenación. Lo llaman tanto SortableJS como el manejador de teclado.
 *
 * @param {boolean} elDomYaEstaMovido SortableJS mueve el nodo él mismo antes de avisar; el teclado
 * no, así que en ese caso hay que moverlo aquí.
 */
function moverTarea(desde, hasta, elDomYaEstaMovido = false) {
	const respaldo = estado.copiarTareas();
	if (!estado.moverTareaDePosicion(desde, hasta)) {
		return;
	}
	if (respaldoAntesDeReordenar === null) {
		respaldoAntesDeReordenar = respaldo;
	}
	if (!elDomYaEstaMovido) {
		vista.moverTarjeta(desde, hasta);
	}

	clearTimeout(temporizadorDeOrden);
	temporizadorDeOrden = setTimeout(guardarOrden, RETARDO_GUARDADO_DE_ORDEN);
}

async function guardarOrden() {
	const respaldo = respaldoAntesDeReordenar;
	respaldoAntesDeReordenar = null;

	const secuencia = anotarNuevaPeticion('orden');
	try {
		const tareas = await api.reordenarTareas(estado.obtenerIdsEnOrden());
		// Si mientras iba la petición el usuario ha movido otra tarea, esta respuesta trae un orden
		// ya caduco: aplicarlo dejaría el estado diciendo algo distinto de lo que se ve.
		if (!esLaPeticionMasReciente('orden', secuencia)) {
			return;
		}
		estado.reemplazarTareas(tareas);
		vista.limpiarError();
	}
	catch (error) {
		deshacerCambioOptimista(respaldo, error);
	}
}

/* ----------------------------------------------------------------- Edición */

function empezarEdicion(id) {
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}
	estado.empezarEdicionDe(id, tarea.texto);
	vista.actualizarTarjeta(id);
}

/**
 * Cierra la edición.
 *
 * La primera comprobación no es cosmética: salir del <textarea> dispara un focusout que vuelve a
 * entrar aquí. Comparar con el estado corta esa reentrada.
 */
function terminarEdicion(id, { cancelar }) {
	if (estado.obtenerIdEnEdicion() !== id) {
		return;
	}
	clearTimeout(temporizadorDeTexto);
	temporizadorDeTexto = null;

	const textoOriginal = estado.obtenerTextoOriginalDeEdicion();
	const tarea = estado.buscarTareaPorId(id);
	let textoFinal;
	if (cancelar) {
		textoFinal = textoOriginal;
	}
	else {
		textoFinal = tarea?.texto ?? '';
	}
	const quedaVacio = textoFinal.trim().length === 0;

	if (cancelar || quedaVacio) {
		// El servidor rechazaría un texto vacío, así que se restaura antes de intentarlo siquiera.
		if (tarea) {
			estado.reemplazarTarea({ ...tarea, texto: textoOriginal });
		}
		if (quedaVacio && !cancelar) {
			vista.mostrarError('El texto de la tarea no puede quedar vacío: se ha restaurado el anterior.');
		}
		estado.terminarEdicion();
		vista.actualizarTarjeta(id);
		return;
	}

	const debeGuardar = textoFinal.trim() !== estado.obtenerTextoGuardadoDeEdicion();
	estado.terminarEdicion();
	vista.actualizarTarjeta(id);

	if (debeGuardar) {
		guardarTexto(id);
	}
}

async function guardarTexto(id) {
	const tarea = estado.buscarTareaPorId(id);
	if (!tarea) {
		return;
	}

	const textoEnviado = tarea.texto.trim();
	if (textoEnviado.length === 0 || textoEnviado === estado.obtenerTextoGuardadoDeEdicion()) {
		return;
	}

	const secuencia = anotarNuevaPeticion(`texto:${id}`);
	try {
		const actualizada = await api.actualizarTarea(id, textoEnviado, tarea.completada);
		if (!esLaPeticionMasReciente(`texto:${id}`, secuencia)) {
			return;
		}
		estado.registrarTextoGuardado(id, actualizada.texto);

		// Si el usuario ha seguido escribiendo mientras iba la petición, su texto es más reciente
		// que la respuesta: aplicarla ahora borraría lo que acaba de teclear.
		if (estado.buscarTareaPorId(id)?.texto.trim() === textoEnviado) {
			estado.reemplazarTarea(actualizada);
			vista.actualizarTarjeta(id);
		}
		vista.limpiarError();
	}
	catch (error) {
		// A diferencia del resto de acciones, aquí NO se deshace el cambio: revertir el texto que el
		// usuario está escribiendo le borraría lo tecleado y le movería el cursor. Se le avisa y su
		// texto sigue en pantalla; el siguiente intento volverá a mandarlo.
		manejarError(error);
	}
}

/**
 * `n` enfoca el campo de nueva tarea.
 *
 * Se ignora si el foco ya está en un campo de texto —si no, no se podría escribir la letra n— y si
 * hay algún modificador, para no pisar los atajos del navegador.
 */
function alPulsarAtajo(evento) {
	if (evento.key !== 'n' || evento.ctrlKey || evento.altKey || evento.metaKey) {
		return;
	}
	// Con el selector de fondo abierto el atajo no pinta nada: el campo de alta queda detrás de un
	// modal y por tanto inerte, así que enfocarlo no haría nada y encima nos comeríamos la tecla.
	const hayDialogoAbierto = document.querySelector('dialog[open]') !== null;
	if (hayDialogoAbierto) {
		return;
	}

	// Se excluyen solo los campos donde se escribe. Las casillas y los botones también son
	// elementos de formulario, pero ahí la «n» no se teclea, así que el atajo debe seguir valiendo.
	const escribiendo = evento.target.closest(
		'textarea, [contenteditable], input:not([type="checkbox"]):not([type="radio"])');
	if (escribiendo) {
		return;
	}

	evento.preventDefault();
	vista.campoTexto.focus();
}

/* ------------------------------------------- Secuencias y manejo de errores */

function anotarNuevaPeticion(operacion) {
	const secuencia = (ultimaSecuenciaPorOperacion.get(operacion) ?? 0) + 1;
	ultimaSecuenciaPorOperacion.set(operacion, secuencia);
	return secuencia;
}

function esLaPeticionMasReciente(operacion, secuencia) {
	const resultado = ultimaSecuenciaPorOperacion.get(operacion) === secuencia;
	return resultado;
}

/** Deshace un cambio optimista que el servidor ha rechazado y explica por qué. */
function deshacerCambioOptimista(respaldo, error) {
	if (respaldo !== null) {
		estado.reemplazarTareas(respaldo);
		vista.pintarLista();
	}
	manejarError(error);
}

/** Único punto donde un error se convierte en algo que el usuario puede leer. */
function manejarError(error) {
	const mensaje = (error instanceof ErrorApi)
		? error.mensajeUsuario
		: 'Ha ocurrido un error inesperado. Vuelve a intentarlo.';

	vista.mostrarError(mensaje);
	vista.anunciar(mensaje);
	console.error(error);
}

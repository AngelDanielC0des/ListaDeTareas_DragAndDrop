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

const formulario = exigirFormularioDeAlta();

/** Falla al cargar, y no en el primer clic, si alguien renombra el id en el HTML. */
function exigirFormularioDeAlta() {
	const elemento = document.getElementById('formulario-nueva');
	if (!(elemento instanceof HTMLFormElement)) {
		throw new Error('Falta en el HTML el formulario #formulario-nueva');
	}
	return elemento;
}

/** @type {ReturnType<typeof setTimeout> | null} */
let temporizadorDeTexto = null;

/** @type {ReturnType<typeof setTimeout> | null} */
let temporizadorDeOrden = null;

/** Estado de la lista antes de la primera reordenación de una ráfaga, por si hay que deshacerla. */
/** @type {import('./tipos.js').Tarea[] | null} */
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
 * @type {{tarea: import('./tipos.js').Tarea, posicion: number, temporizador: ReturnType<typeof setTimeout>} | null}
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
 * Van en tres peticiones porque son tres recursos distintos en el servidor —una tarea son tres
 * campos, y el fondo y el grupo viven aparte—, pero se lanzan en paralelo y se pinta una sola vez.
 */
async function cargarTareas() {
	try {
		const [tareas, fondos, grupos] = await Promise.all([
			api.listarTareas(), api.consultarFondos(), api.consultarGrupos()
		]);
		estado.reemplazarTareas(tareas);
		estado.reemplazarFondos(fondos);
		estado.reemplazarGrupos(grupos);
		repintar();
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

	vista.secciones.addEventListener('click', alPulsarEnLista);
	vista.secciones.addEventListener('change', alCambiarCasilla);
	vista.secciones.addEventListener('input', alEscribirEnEditor);
	vista.secciones.addEventListener('keydown', (evento) => alTeclearEnEditor(/** @type {KeyboardEvent} */ (evento)));
	// El foco no burbujea, pero focusout sí, que es lo que permite delegarlo en el contenedor.
	vista.secciones.addEventListener('focusout', alSalirDelEditor);

	// Alta de tareas, plegada tras un «+».
	vista.botonAbrirAlta.addEventListener('click', () => vista.mostrarAlta(true));
	vista.botonCancelarAlta.addEventListener('click', () => vista.mostrarAlta(false));

	// Alta de grupos, con el mismo patrón.
	vista.botonAbrirGrupo.addEventListener('click', () => vista.mostrarAltaDeGrupo(true));
	vista.botonCancelarGrupo.addEventListener('click', () => vista.mostrarAltaDeGrupo(false));
	vista.formularioGrupo.addEventListener('submit', (evento) => {
		evento.preventDefault();
		crearGrupo(vista.campoGrupo.value);
	});

	// Las acciones de una cabecera de grupo van delegadas en el contenedor, igual que las de las
	// tarjetas: las secciones se repintan enteras y colgar listeners de cada una los perdería.
	vista.secciones.addEventListener('click', alPulsarEnCabecera);

	vista.alCambiarElFiltro((filtro) => aplicarFiltro(filtro));

	vista.alPulsarVerAtajos(() => vista.alternarAtajos());

	vista.alPulsarElTema(() => {
		const tema = vista.temaContrarioAlActual();
		preferencias.guardarTema(tema);
		vista.aplicarTema(tema);
	});

	document.addEventListener('keydown', (evento) => alPulsarAtajo(evento));

	// Si la pestaña se cierra con un borrado a medias, se manda igualmente: si no, la tarea
	// reaparecería al volver y el borrado parecería no haber funcionado.
	globalThis.addEventListener('pagehide', alCerrarLaPagina);

	// Red de seguridad: ningún fallo asíncrono debe quedarse mudo.
	globalThis.addEventListener('unhandledrejection', (evento) => manejarError(evento.reason));
}

/**
 * Repinta y vuelve a enganchar el arrastre.
 *
 * Va junto en una sola función porque `pintarLista()` reemplaza las secciones enteras: las
 * instancias de SortableJS quedarían apuntando a nodos que ya no están en el documento, y el
 * arrastre dejaría de responder sin dar ninguna pista de por qué.
 */
function repintar() {
	// El reenganche va DENTRO del pintado, no después: `pintarLista()` aplaza el cambio del DOM
	// dentro de una transición de vista, así que al volver de aquí las listas nuevas todavía no
	// existen y el arrastre se ataría a las viejas.
	vista.pintarLista(() => {
		arrastre.rehacer();
		arrastre.permitirReordenar(!estado.hayFiltroActivo());
	});
}

/* ------------------------------------------------------------- Manejadores */

/**
 * Reparte los clics de la lista según el botón que se haya pulsado.
 *
 * Los casos son excluyentes —un botón es uno de los cuatro— así que se escriben como un `switch` y
 * no como cuatro `if` sueltos con su `return`: de un vistazo se ve que forman una sola decisión y
 * que están todos contemplados.
 */
/** @param {Event} evento */
function alPulsarEnLista(evento) {
	if (!(evento.target instanceof Element)) {
		return;
	}
	const boton = evento.target.closest('button');
	const id = idDeLaTarjetaDe(boton);
	if (boton === null || id === null) {
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
/** @param {number} id */
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
 *
 * @param {Element | null | undefined} elemento
 * @returns {number | null}
 */
function idDeLaTarjetaDe(elemento) {
	const tarjeta = elemento?.closest('.tarea');

	/** @type {number | null} */
	let resultado = null;
	if (tarjeta instanceof HTMLElement) {
		resultado = Number(tarjeta.dataset.id);
	}
	return resultado;
}

/** @param {Event} evento */
function alCambiarCasilla(evento) {
	const casilla = (evento.target instanceof Element) ? evento.target.closest('.tarea__casilla') : null;
	if (!(casilla instanceof HTMLInputElement)) {
		return;
	}
	const id = idDeLaTarjetaDe(casilla);
	if (id !== null) {
		cambiarCompletada(id, casilla.checked);
	}
}

/** @param {Event} evento */
function alEscribirEnEditor(evento) {
	const editor = (evento.target instanceof Element) ? evento.target.closest('.tarea__editor') : null;
	if (!(editor instanceof HTMLTextAreaElement)) {
		return;
	}
	const id = idDeLaTarjetaDe(editor);
	const tarea = (id === null) ? null : estado.buscarTareaPorId(id);
	if (id === null || tarea === null) {
		return;
	}

	// Se actualiza el estado sin refrescar la tarjeta: hacerlo movería el cursor a cada tecla.
	estado.reemplazarTarea({ ...tarea, texto: editor.value });

	clearTimeout(temporizadorDeTexto ?? undefined);
	temporizadorDeTexto = setTimeout(() => guardarTexto(id), RETARDO_GUARDADO_DE_TEXTO);
}

/** @param {KeyboardEvent} evento */
function alTeclearEnEditor(evento) {
	const editor = (evento.target instanceof Element) ? evento.target.closest('.tarea__editor') : null;
	if (!(editor instanceof HTMLTextAreaElement)) {
		return;
	}
	const id = idDeLaTarjetaDe(editor);
	if (id === null) {
		return;
	}

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

/** @param {Event} evento */
function alSalirDelEditor(evento) {
	const editor = (evento.target instanceof Element) ? evento.target.closest('.tarea__editor') : null;
	if (!(editor instanceof HTMLTextAreaElement)) {
		return;
	}
	const id = idDeLaTarjetaDe(editor);
	if (id !== null) {
		terminarEdicion(id, { cancelar: false });
	}
}

/**
 * Aplica el filtro y repinta.
 *
 * Al filtrar se desactiva la reordenación: con parte de la lista oculta, las posiciones que ve el
 * usuario no son las del estado, y arrastrar produciría un orden que no significa lo que parece.
 *
 * @param {{estado: string, busqueda: string}} filtro
 */
function aplicarFiltro(filtro) {
	estado.filtrarPorEstado(/** @type {'todas' | 'pendientes' | 'completadas'} */ (filtro.estado));
	estado.buscar(filtro.busqueda);

	repintar();
	arrastre.permitirReordenar(!estado.hayFiltroActivo());
	vista.anunciar(vista.describirResultados());
}

/* ----------------------------------------------------------------- Acciones */

/** @param {string} texto */
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
		repintar();
		vista.limpiarError();
		vista.anunciar(`Tarea añadida: ${creada.texto}`);
	}
	catch (error) {
		manejarError(error);
	}
	finally {
		vista.bloquearAlta(false);
		// El alta se queda abierta y con el foco dentro: encadenar varias tareas seguidas es lo
		// normal, y obligar a volver a pulsar «+» cada vez sería un paso de más.
		vista.campoTexto.focus();
	}
}

/**
 * @param {number} id
 * @param {boolean} completada
 */
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
 *
 * @param {number} id
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
	repintar();
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
	repintar();
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
		repintar();
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
	clearTimeout(temporizadorDeTexto ?? undefined);
	temporizadorDeTexto = null;

	const id = estado.obtenerIdEnEdicion();
	const tarea = (id === null) ? null : estado.buscarTareaPorId(id);
	if (id === null || tarea === null) {
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
	clearTimeout(temporizadorDeOrden ?? undefined);
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

/* ------------------------------------------------------------------ Grupos */

/** @param {Event} evento */
function alPulsarEnCabecera(evento) {
	if (!(evento.target instanceof Element)) {
		return;
	}
	const boton = evento.target.closest('.seccion__accion');
	const seccion = boton?.closest('.seccion');
	if (!(boton instanceof HTMLElement) || !(seccion instanceof HTMLElement)) {
		return;
	}

	const idGrupo = Number(seccion.dataset.grupo);
	if (boton.dataset.accion === 'renombrar-grupo') {
		renombrarGrupo(idGrupo);
	}
	else if (boton.dataset.accion === 'borrar-grupo') {
		borrarGrupo(idGrupo);
	}
}

/** @param {string} nombre */
async function crearGrupo(nombre) {
	const nombreLimpio = nombre.trim();
	if (nombreLimpio.length === 0) {
		vista.mostrarError('Escribe un nombre para el grupo.');
		vista.campoGrupo.focus();
		return;
	}

	try {
		await api.crearGrupo(nombreLimpio);
		estado.reemplazarGrupos(await api.consultarGrupos());
		vista.mostrarAltaDeGrupo(false);
		repintar();
		vista.limpiarError();
		vista.anunciar(`Grupo creado: ${nombreLimpio}`);
	}
	catch (error) {
		manejarError(error);
	}
}

/**
 * Renombra un grupo.
 *
 * Se pide el nombre con `prompt()` a propósito: es la única parte de la aplicación que lo usa, y la
 * alternativa —otro diálogo más con su formulario— añadiría bastante interfaz para una acción que se
 * hace muy de vez en cuando. Si algún día se hace habitual, aquí es donde tocaría cambiarlo.
 *
 * @param {number} idGrupo
 */
async function renombrarGrupo(idGrupo) {
	const actual = estado.obtenerGrupos().find((grupo) => grupo.id === idGrupo);
	if (actual === undefined) {
		return;
	}

	const nombre = globalThis.prompt('Nuevo nombre del grupo:', actual.nombre);
	if (nombre === null || nombre.trim() === '' || nombre.trim() === actual.nombre) {
		return;
	}

	try {
		await api.renombrarGrupo(idGrupo, nombre.trim());
		estado.reemplazarGrupos(await api.consultarGrupos());
		repintar();
		vista.limpiarError();
		vista.anunciar(`Grupo renombrado a ${nombre.trim()}.`);
	}
	catch (error) {
		manejarError(error);
	}
}

/**
 * Borra un grupo tras confirmarlo.
 *
 * Aquí sí se pregunta antes, al revés que con las tareas: borrar un grupo afecta a todas las que
 * contiene y no hay un «deshacer» que lo devuelva. Se avisa de que las tareas **no** se borran,
 * porque es justo lo que teme quien duda antes de pulsar.
 *
 * @param {number} idGrupo
 */
async function borrarGrupo(idGrupo) {
	const grupo = estado.obtenerGrupos().find((cual) => cual.id === idGrupo);
	if (grupo === undefined) {
		return;
	}

	const cuantas = estado.contarProgresoDeGrupo(idGrupo).total;
	const aviso = (cuantas === 0) ? `¿Borrar el grupo «${grupo.nombre}»?`
		: `¿Borrar el grupo «${grupo.nombre}»? Sus ${cuantas} tareas no se borran: quedarán sueltas.`;
	if (!globalThis.confirm(aviso)) {
		return;
	}

	try {
		await api.eliminarGrupo(idGrupo);
		estado.reemplazarGrupos(await api.consultarGrupos());
		repintar();
		vista.limpiarError();
		vista.anunciar(`Grupo borrado: ${grupo.nombre}. Sus tareas quedan sueltas.`);
	}
	catch (error) {
		manejarError(error);
	}
}

/* ------------------------------------------------------- Fondo de la tarjeta */

/**
 * Abre el selector de fondo y guarda la elección.
 *
 * El servidor devuelve el mapa de fondos ya actualizado, así que no hace falta recomponerlo aquí ni
 * dar por hecho que la escritura ha ido bien: se pinta lo que el servidor dice que hay.
 *
 * @param {number} id
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
 * Recibe solo qué tarea, a qué grupo y a qué posición dentro de esa sección; el orden global lo
 * recompone `estado.js`. Después se repinta entero en vez de mover el nodo a mano: al cambiar de
 * grupo cambian también las cuentas y las barras de progreso de dos secciones, y llevar eso a mano
 * sería más código y más frágil que volver a pintar.
 *
 * @param {number} id
 * @param {number | null} idGrupo
 * @param {number} posicionEnGrupo
 */
function moverTarea(id, idGrupo, posicionEnGrupo) {
	const respaldo = estado.copiarTareas();
	const grupoAnterior = estado.obtenerGrupoDe(id);

	if (!estado.moverTareaAGrupo(id, idGrupo, posicionEnGrupo)) {
		return;
	}
	if (respaldoAntesDeReordenar === null) {
		respaldoAntesDeReordenar = respaldo;
	}
	repintar();

	// Si ha cambiado de sección hay que contarlo aparte: el orden y la pertenencia son dos recursos
	// distintos en el servidor.
	if (grupoAnterior !== idGrupo) {
		guardarGrupo(id, idGrupo);
	}

	clearTimeout(temporizadorDeOrden ?? undefined);
	temporizadorDeOrden = setTimeout(guardarOrden, RETARDO_GUARDADO_DE_ORDEN);
}

/**
 * @param {number} id
 * @param {number | null} idGrupo
 */
async function guardarGrupo(id, idGrupo) {
	try {
		estado.reemplazarGrupos(await api.cambiarGrupo(id, idGrupo));
		vista.limpiarError();
	}
	catch (error) {
		manejarError(error);
	}
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

/** @param {number} id */
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
 *
 * @param {number} id
 * @param {{cancelar: boolean}} opciones
 */
function terminarEdicion(id, { cancelar }) {
	if (estado.obtenerIdEnEdicion() !== id) {
		return;
	}
	clearTimeout(temporizadorDeTexto ?? undefined);
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
		if (tarea !== null) {
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

/** @param {number} id */
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
 * `n` enfoca el campo de nueva tarea, `/` el de búsqueda y `?` abre la ayuda de atajos.
 *
 * Se ignoran si el foco ya está en un campo de texto —si no, no se podrían teclear— y si hay algún
 * modificador, para no pisar los atajos del navegador.
 *
 * @param {KeyboardEvent} evento
 */
function alPulsarAtajo(evento) {
	const esAtajoConocido = evento.key === 'n' || evento.key === '/' || evento.key === '?'
		|| evento.key === 'Escape';
	if (!esAtajoConocido || evento.ctrlKey || evento.altKey || evento.metaKey) {
		return;
	}

	// Escape cierra el alta que esté abierta antes que nada.
	if (evento.key === 'Escape') {
		cerrarLoQueEsteAbierto();
		return;
	}

	// Con un diálogo abierto los campos de detrás quedan inertes, así que enfocarlos no haría nada y
	// encima nos comeríamos la tecla. La ayuda es la excepción: «?» también sirve para cerrarla.
	const hayDialogoAbierto = document.querySelector('dialog[open]') !== null;
	if (hayDialogoAbierto && evento.key !== '?') {
		return;
	}

	// Se excluyen solo los campos donde se escribe. Las casillas y los botones también son
	// elementos de formulario, pero ahí la «n» no se teclea, así que el atajo debe seguir valiendo.
	const escribiendo = (evento.target instanceof Element) && evento.target.closest(
		'textarea, [contenteditable], input:not([type="checkbox"]):not([type="radio"])');
	if (escribiendo) {
		return;
	}

	evento.preventDefault();
	if (evento.key === '?') {
		vista.alternarAtajos();
	}
	else if (evento.key === '/') {
		vista.campoBusqueda.focus();
	}
	else {
		vista.mostrarAlta(true);
	}
}

/** Escape cierra el formulario que esté desplegado, devolviendo el foco a su botón. */
function cerrarLoQueEsteAbierto() {
	if (vista.estaAbiertaElAlta()) {
		vista.mostrarAlta(false);
	}
	else if (vista.estaAbiertaElAltaDeGrupo()) {
		vista.mostrarAltaDeGrupo(false);
	}
}

/* ------------------------------------------- Secuencias y manejo de errores */

/**
 * @param {string} operacion
 * @returns {number}
 */
function anotarNuevaPeticion(operacion) {
	const secuencia = (ultimaSecuenciaPorOperacion.get(operacion) ?? 0) + 1;
	ultimaSecuenciaPorOperacion.set(operacion, secuencia);
	return secuencia;
}

/**
 * @param {string} operacion
 * @param {number} secuencia
 */
function esLaPeticionMasReciente(operacion, secuencia) {
	const resultado = ultimaSecuenciaPorOperacion.get(operacion) === secuencia;
	return resultado;
}

/** Deshace un cambio optimista que el servidor ha rechazado y explica por qué. *
 * @param {import('./tipos.js').Tarea[] | null} respaldo
 * @param {unknown} error
 */
function deshacerCambioOptimista(respaldo, error) {
	if (respaldo !== null) {
		estado.reemplazarTareas(respaldo);
		repintar();
	}
	manejarError(error);
}

/** Único punto donde un error se convierte en algo que el usuario puede leer. *
 * @param {unknown} error
 */
function manejarError(error) {
	const mensaje = (error instanceof ErrorApi)
		? error.mensajeUsuario
		: 'Ha ocurrido un error inesperado. Vuelve a intentarlo.';

	vista.mostrarError(mensaje);
	vista.anunciar(mensaje);
	console.error(error);
}

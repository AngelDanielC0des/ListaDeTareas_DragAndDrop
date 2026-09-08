/**
 * Todo lo que toca el DOM vive aquí.
 *
 * La vista no decide nada: recibe el estado y lo pinta. Y no construye HTML concatenando cadenas,
 * clona la <template> del index y rellena con textContent, de modo que el texto que escribe el
 * usuario nunca puede interpretarse como marcado (XSS).
 *
 * Hay tres niveles de actualización, de más caro a más barato, y cada acción usa el que le
 * corresponde. Antes todo pasaba por una reconstrucción completa: marcar una casilla con 50 tareas
 * clonaba 50 tarjetas y destruía otras 50, y de paso se cargaba el <textarea> si estabas editando.
 *
 *   pintarLista()          la lista entera   -> carga inicial, añadir, borrar
 *   moverTarjeta()         mueve un nodo     -> reordenar
 *   actualizarTarjeta(id)  una sola tarjeta  -> completada, desplegar texto, entrar/salir de edición
 */

import * as estado from './estado.js';

const elementos = {
	lista: document.getElementById('lista'),
	plantilla: document.getElementById('plantilla-tarea'),
	listaVacia: document.getElementById('lista-vacia'),
	avisoError: document.getElementById('aviso-error'),
	resumen: document.getElementById('resumen'),
	contador: document.getElementById('contador'),
	campoTexto: document.getElementById('campo-texto'),
	botonAnadir: document.getElementById('boton-anadir'),
	progreso: document.getElementById('progreso'),
	radiosDeTema: document.querySelectorAll('.tema__radio'),
	avisoDeshacer: document.getElementById('aviso-deshacer'),
	textoDeshacer: document.getElementById('aviso-deshacer-texto'),
	botonDeshacer: document.getElementById('boton-deshacer'),
	anuncios: document.getElementById('anuncios'),
	selectorFondo: document.getElementById('selector-fondo'),
	nombreTareaEnSelector: document.getElementById('selector-fondo-tarea'),
	opcionesDeFondo: document.getElementById('selector-fondo-opciones')
};

/** Se exponen para que `app.js` y `arrastre.js` cuelguen sus listeners sin volver a buscarlos. */
export const lista = elementos.lista;

export const campoTexto = elementos.campoTexto;

/**
 * Bloquea el botón de añadir mientras va la petición.
 *
 * Sin esto, pulsar Enter dos veces seguidas manda dos POST y crea la tarea por duplicado. Aquí no
 * vale la interfaz optimista: el id lo asigna el servidor, así que hay que esperar su respuesta.
 */
export function bloquearAlta(bloqueado) {
	elementos.botonAnadir.disabled = bloqueado;
}

/** Lo fija el servidor al arrancar; hasta entonces no se limita nada por el lado del navegador. */
let maxCaracteresTexto = null;

/**
 * Aplica el límite de caracteres que dicta el servidor.
 *
 * El número no está escrito en el HTML ni en este archivo a propósito: la única fuente de verdad es
 * `Tarea.MAX_CARACTERES_TEXTO` en Java, y llega por el endpoint de configuración.
 */
export function configurarLimiteDeTexto(maximo) {
	maxCaracteresTexto = maximo;
	elementos.campoTexto.maxLength = maximo;
	for (const editor of elementos.lista.querySelectorAll('.tarea__editor')) {
		editor.maxLength = maximo;
	}
	actualizarContador();
}

/* --------------------------------------------------------------- Pintar */

/** Reconstruye la lista completa. Solo para cambios estructurales. */
export function pintarLista() {
	const tareas = estado.obtenerTareas();
	const idEnEdicion = estado.obtenerIdEnEdicion();

	const fragmento = document.createDocumentFragment();
	for (const tarea of tareas) {
		fragmento.appendChild(construirTarjeta(tarea, idEnEdicion === tarea.id));
	}

	elementos.lista.replaceChildren(fragmento);
	elementos.listaVacia.hidden = tareas.length > 0;

	actualizarBotonesVerMas();
	actualizarResumen();
	enfocarEditorEnEdicion();
}

/**
 * Actualiza una sola tarjeta sin tocar las demás.
 *
 * Es lo que se usa al marcar completada, desplegar el texto o entrar y salir de edición: cambios
 * que no alteran ni cuántas tareas hay ni en qué orden están.
 */
export function actualizarTarjeta(id) {
	const tarjeta = obtenerTarjetaDeTarea(id);
	const tarea = estado.buscarTareaPorId(id);
	if (!tarjeta || !tarea) {
		return;
	}

	rellenarTarjeta(tarjeta, tarea, estado.obtenerIdEnEdicion() === id);
	actualizarBotonVerMasDe(tarjeta, medirDesbordamiento(tarjeta));
	actualizarResumen();
	enfocarEditorEnEdicion();
}

/**
 * Mueve una tarjeta de posición sin reconstruir la lista.
 *
 * Reordenar solo cambia el sitio de un nodo, así que rehacer las N tarjetas sería tirar y volver a
 * crear todo lo que ya estaba bien. Además, moviendo el nodo sobrevive un <textarea> abierto.
 */
export function moverTarjeta(desde, hasta) {
	const tarjeta = elementos.lista.children[desde];
	if (!tarjeta) {
		return;
	}
	// Al mover hacia abajo, el nodo de destino se «corre» una posición en cuanto se saca el actual,
	// así que la referencia es el siguiente.
	const referencia = elementos.lista.children[hasta > desde ? hasta + 1 : hasta] ?? null;
	elementos.lista.insertBefore(tarjeta, referencia);
}

function construirTarjeta(tarea, enEdicion) {
	const tarjeta = elementos.plantilla.content.firstElementChild.cloneNode(true);
	tarjeta.dataset.id = String(tarea.id);
	rellenarTarjeta(tarjeta, tarea, enEdicion);
	return tarjeta;
}

/** Vuelca una tarea sobre una tarjeta, exista ya o acabe de clonarse. */
function rellenarTarjeta(tarjeta, tarea, enEdicion) {
	tarjeta.classList.toggle('tarea--completada', tarea.completada);
	tarjeta.classList.toggle('tarea--desplegada', estado.estaDesplegada(tarea.id));

	const casilla = tarjeta.querySelector('.tarea__casilla');
	const etiqueta = tarjeta.querySelector('label');
	casilla.checked = tarea.completada;
	casilla.id = `casilla-${tarea.id}`;
	etiqueta.htmlFor = casilla.id;
	etiqueta.textContent = tarea.completada ? `Marcar «${tarea.texto}» como pendiente`
		: `Marcar «${tarea.texto}» como completada`;

	// Cada botón dice de qué tarea es. Sin esto, un lector de pantalla recorre la lista diciendo
	// «Editar, botón», «Borrar, botón»… sin nombrar nunca la tarea a la que pertenecen.
	tarjeta.querySelector('.tarea__asa').setAttribute('aria-label', `Mover la tarea «${tarea.texto}»`);
	tarjeta.querySelector('[data-accion="editar"]').setAttribute('aria-label', `Editar la tarea «${tarea.texto}»`);
	tarjeta.querySelector('[data-accion="eliminar"]').setAttribute('aria-label', `Borrar la tarea «${tarea.texto}»`);
	tarjeta.querySelector('[data-accion="fondo"]')
		.setAttribute('aria-label', `Elegir el fondo de la tarea «${tarea.texto}»`);

	aplicarFondo(tarjeta, estado.obtenerFondoDe(tarea.id));

	const parrafo = tarjeta.querySelector('.tarea__texto');
	parrafo.textContent = tarea.texto;

	const editor = tarjeta.querySelector('.tarea__editor');
	editor.setAttribute('aria-label', 'Editar el texto de la tarea');
	if (maxCaracteresTexto !== null) {
		editor.maxLength = maxCaracteresTexto;
	}
	// Si el usuario está escribiendo AHÍ, pisarle el valor le movería el cursor y le borraría lo que
	// acabase de teclear. Su texto es más reciente que el del estado.
	if (editor.value !== tarea.texto && document.activeElement !== editor) {
		editor.value = tarea.texto;
	}

	parrafo.hidden = enEdicion;
	editor.hidden = !enEdicion;
	tarjeta.querySelector('[data-accion="editar"] .tarea__accion-texto').textContent = enEdicion ? 'Listo' : 'Editar';
}

/* --------------------------------------------------------- Recorte del texto */

/**
 * Decide qué tarjetas necesitan el botón «Ver más».
 *
 * Va en DOS pasadas —medir todas y después escribir todas— y no en un único bucle. Mezclarlas
 * provocaba «layout thrashing»: cada escritura invalidaba el layout, así que la siguiente lectura de
 * scrollHeight obligaba al navegador a recalcularlo. Eran O(n) reflujos forzados donde basta con uno.
 */
function actualizarBotonesVerMas() {
	const tarjetas = [...elementos.lista.children];

	const desbordamientos = tarjetas.map((tarjeta) => medirDesbordamiento(tarjeta));

	tarjetas.forEach((tarjeta, indice) => actualizarBotonVerMasDe(tarjeta, desbordamientos[indice]));
}

/** Pasada de LECTURA: solo consulta el DOM, no lo modifica. */
function medirDesbordamiento(tarjeta) {
	const parrafo = tarjeta.querySelector('.tarea__texto');
	if (parrafo.hidden) {
		return false;
	}
	const resultado = parrafo.scrollHeight > parrafo.clientHeight + 1;
	return resultado;
}

/** Pasada de ESCRITURA: solo modifica el DOM, no lo consulta. */
function actualizarBotonVerMasDe(tarjeta, desborda) {
	const boton = tarjeta.querySelector('.tarea__boton-desplegar');
	const parrafo = tarjeta.querySelector('.tarea__texto');

	if (parrafo.hidden) {
		boton.hidden = true;
		return;
	}
	// Ya desplegada: al no haber recorte no se puede medir el desbordamiento, pero el botón tiene
	// que seguir ahí para poder volver a plegarla.
	if (estado.estaDesplegada(Number(tarjeta.dataset.id))) {
		boton.hidden = false;
		boton.textContent = 'Ver menos';
		boton.setAttribute('aria-expanded', 'true');
		return;
	}
	boton.hidden = !desborda;
	boton.textContent = 'Ver más';
	boton.setAttribute('aria-expanded', 'false');
}

/**
 * Al cambiar el ancho cambian las líneas que caben, así que hay que volver a decidir qué tarjetas
 * necesitan «Ver más». Sin esto, girar el móvil dejaría botones sobrando o faltando.
 *
 * Se agrupa con requestAnimationFrame porque el callback escribe en el DOM: hacerlo de forma
 * síncrona dentro del propio observador provoca el aviso «ResizeObserver loop completed with
 * undelivered notifications» y pasadas de más.
 */
export function vigilarCambiosDeAncho() {
	if (typeof ResizeObserver === 'undefined') {
		return;
	}
	let repasoPendiente = false;
	const observador = new ResizeObserver(() => {
		if (repasoPendiente) {
			return;
		}
		repasoPendiente = true;
		requestAnimationFrame(() => {
			repasoPendiente = false;
			actualizarBotonesVerMas();
		});
	});
	observador.observe(elementos.lista);
}

/* ------------------------------------------------------------------ Auxiliares */

function enfocarEditorEnEdicion() {
	const idEnEdicion = estado.obtenerIdEnEdicion();
	if (idEnEdicion === null) {
		return;
	}
	const editor = obtenerEditorDeTarea(idEnEdicion);
	if (editor && document.activeElement !== editor) {
		editor.focus();
		editor.setSelectionRange(editor.value.length, editor.value.length);
	}
}

function obtenerEditorDeTarea(id) {
	const resultado = elementos.lista.querySelector(`[data-id="${id}"] .tarea__editor`);
	return resultado;
}

export function obtenerTarjetaDeTarea(id) {
	const resultado = elementos.lista.querySelector(`[data-id="${id}"]`);
	return resultado;
}

/**
 * Refresca el resumen, la barra de progreso y el título de la pestaña.
 *
 * El título lleva el número de pendientes para que se vea sin traer la pestaña al frente. La barra
 * es un {@code <progress>} con aria-hidden: el texto de al lado ya dice lo mismo y anunciarlo dos
 * veces resulta molesto con un lector de pantalla.
 */
function actualizarResumen() {
	const tareas = estado.obtenerTareas();
	const completadas = tareas.filter((tarea) => tarea.completada).length;
	const pendientes = tareas.length - completadas;

	elementos.resumen.textContent = (tareas.length === 0) ? 'Sin tareas.'
		: `${completadas} de ${tareas.length} completadas.`;

	// max=1 con value=0 cuando no hay tareas: evita una división por cero y deja la barra vacía.
	elementos.progreso.max = Math.max(tareas.length, 1);
	elementos.progreso.value = completadas;
	elementos.progreso.hidden = tareas.length === 0;

	document.title = pendientes > 0 ? `(${pendientes}) Mis tareas` : 'Mis tareas';
}

export function actualizarContador() {
	if (maxCaracteresTexto === null) {
		return;
	}
	const usados = elementos.campoTexto.value.length;
	elementos.contador.textContent = `${usados} / ${maxCaracteresTexto}`;
	elementos.contador.classList.toggle('formulario__contador--limite', usados >= maxCaracteresTexto);
}

/* ---------------------------------------------------------------- Fondos */

/** Los cinco fondos, con el nombre que se enseña. El «ninguno» es el valor por defecto. */
const FONDOS = [
	{ valor: 'ninguno', nombre: 'Sin fondo' },
	{ valor: 'ondas', nombre: 'Ondas' },
	{ valor: 'puntos', nombre: 'Puntos' },
	{ valor: 'lineas', nombre: 'Líneas' },
	{ valor: 'papel', nombre: 'Papel' },
	{ valor: 'aurora', nombre: 'Aurora' }
];

function aplicarFondo(tarjeta, fondo) {
	for (const opcion of FONDOS) {
		tarjeta.classList.toggle(`tarea--fondo-${opcion.valor}`, opcion.valor === fondo && fondo !== 'ninguno');
	}
	tarjeta.classList.toggle('tarea--con-fondo', fondo !== 'ninguno');
}

/**
 * Abre el selector de fondo de una tarea.
 *
 * <p>Se usa `showModal()` y no `show()`: es lo que atrapa el foco dentro del diálogo, oscurece el
 * resto y habilita el cierre con Escape. Al cerrarse, el navegador devuelve el foco al botón que lo
 * abrió sin que haya que guardarlo a mano.
 */
export function abrirSelectorDeFondo(tarea, fondoActual, alElegir) {
	elementos.nombreTareaEnSelector.textContent = tarea.texto;

	const fragmento = document.createDocumentFragment();
	for (const opcion of FONDOS) {
		const elemento = document.createElement('li');
		const boton = document.createElement('button');
		boton.type = 'button';
		boton.className = 'selector-fondo__boton';
		boton.setAttribute('aria-pressed', String(opcion.valor === fondoActual));

		const muestra = document.createElement('span');
		muestra.className = 'selector-fondo__muestra';
		if (opcion.valor !== 'ninguno') {
			muestra.style.backgroundImage = `url("img/${opcion.valor}.svg")`;
		}

		const nombre = document.createElement('span');
		nombre.textContent = opcion.nombre;

		boton.append(muestra, nombre);
		boton.addEventListener('click', () => alElegir(opcion.valor));
		elemento.appendChild(boton);
		fragmento.appendChild(elemento);
	}

	elementos.opcionesDeFondo.replaceChildren(fragmento);
	elementos.selectorFondo.showModal();
}

export function cerrarSelectorDeFondo() {
	if (elementos.selectorFondo.open) {
		elementos.selectorFondo.close();
	}
}

/* ----------------------------------------------------------------------- Tema */

/**
 * Aplica el tema elegido.
 *
 * «Sistema» se traduce en quitar el atributo, no en poner un valor: sin él manda el
 * `prefers-color-scheme` de la hoja de estilos, que es exactamente lo que significa esa opción.
 */
export function aplicarTema(tema) {
	if (tema === 'claro' || tema === 'oscuro') {
		document.documentElement.dataset.tema = tema;
	}
	else {
		delete document.documentElement.dataset.tema;
	}

	for (const radio of elementos.radiosDeTema) {
		radio.checked = (radio.value === tema);
	}
}

export function alCambiarElTema(manejador) {
	for (const radio of elementos.radiosDeTema) {
		radio.addEventListener('change', () => manejador(radio.value));
	}
}

/* ------------------------------------------------------- Aviso de deshacer */

export function mostrarDeshacer(textoDeLaTarea, alPulsarDeshacer) {
	elementos.textoDeshacer.textContent = `Tarea borrada: «${textoDeLaTarea}»`;
	elementos.avisoDeshacer.hidden = false;
	elementos.botonDeshacer.onclick = alPulsarDeshacer;
}

/**
 * Lleva el foco al botón de deshacer tras borrar.
 *
 * Al desaparecer la tarjeta, el foco se caería al `body` y quien navega con teclado tendría que
 * recorrer la página otra vez desde el principio. Además el deshacer solo dura unos segundos, así
 * que conviene que sea lo primero que se alcance.
 */
export function enfocarDeshacer() {
	elementos.botonDeshacer.focus();
}

/** Devuelve el foco a una tarjeta concreta, por ejemplo tras deshacer un borrado. */
export function enfocarTarjeta(id) {
	obtenerTarjetaDeTarea(id)?.querySelector('.tarea__casilla')?.focus();
}

export function ocultarDeshacer() {
	elementos.avisoDeshacer.hidden = true;
	elementos.botonDeshacer.onclick = null;
}

/* --------------------------------------------------------------------- Avisos */

/** Único punto donde se muestra un error al usuario, sea de red, de validación o del servidor. */
export function mostrarError(mensaje) {
	elementos.avisoError.textContent = mensaje;
	elementos.avisoError.hidden = false;
}

export function limpiarError() {
	elementos.avisoError.textContent = '';
	elementos.avisoError.hidden = true;
}

/** Mensaje solo para lectores de pantalla: cambios que se ven pero no se «oyen». */
export function anunciar(mensaje) {
	elementos.anuncios.textContent = mensaje;
}

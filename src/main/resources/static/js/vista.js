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

/**
 * Busca un elemento por id y falla si no está.
 *
 * El `getElementById` del navegador devuelve `null` cuando no encuentra nada, y ese `null` viajaba
 * silenciosamente hasta el primer intento de usarlo, mucho más tarde y lejos de la causa. Aquí se
 * corta en el arranque: si alguien renombra un id en el HTML, la consola lo dice al cargar la página
 * y señala cuál falta.
 *
 * El segundo parámetro comprueba además que el elemento sea del tipo que se espera, para poder
 * escribir `campoTexto.value` sabiendo que de verdad es un `<input>` y no un `<div>` cualquiera.
 *
 * @template {typeof Element} T
 * @param {string} id
 * @param {T} tipo
 * @returns {InstanceType<T>}
 */
function exigirElemento(id, tipo) {
	const elemento = document.getElementById(id);
	if (!(elemento instanceof tipo)) {
		throw new Error(`Falta en el HTML el elemento #${id} (se esperaba un ${tipo.name})`);
	}
	return /** @type {InstanceType<T>} */ (elemento);
}

/**
 * Lo mismo que {@link exigirElemento} pero buscando dentro de un elemento y por selector.
 *
 * Se usa para las partes de una tarjeta, que salen de clonar la plantilla: si alguien renombra una
 * clase ahi, esto lo dice en el momento en vez de dejar un `null` suelto.
 *
 * @template {typeof Element} T
 * @param {ParentNode} raiz
 * @param {string} selector
 * @param {T} tipo
 * @returns {InstanceType<T>}
 */
function exigirDentro(raiz, selector, tipo) {
	const elemento = raiz.querySelector(selector);
	if (!(elemento instanceof tipo)) {
		throw new Error(`Falta en la plantilla el elemento «${selector}» (se esperaba un ${tipo.name})`);
	}
	return /** @type {InstanceType<T>} */ (elemento);
}

const elementos = {
	lista: exigirElemento('lista', HTMLUListElement),
	plantilla: exigirElemento('plantilla-tarea', HTMLTemplateElement),
	listaVacia: exigirElemento('lista-vacia', HTMLElement),
	avisoError: exigirElemento('aviso-error', HTMLElement),
	resumen: exigirElemento('resumen', HTMLElement),
	contador: exigirElemento('contador', HTMLElement),
	campoTexto: exigirElemento('campo-texto', HTMLInputElement),
	botonAnadir: exigirElemento('boton-anadir', HTMLButtonElement),
	progreso: exigirElemento('progreso', HTMLProgressElement),
	radiosDeTema: document.querySelectorAll('.tema__radio'),
	avisoDeshacer: exigirElemento('aviso-deshacer', HTMLElement),
	textoDeshacer: exigirElemento('aviso-deshacer-texto', HTMLElement),
	botonDeshacer: exigirElemento('boton-deshacer', HTMLButtonElement),
	anuncios: exigirElemento('anuncios', HTMLElement),
	selectorFondo: exigirElemento('selector-fondo', HTMLDialogElement),
	nombreTareaEnSelector: exigirElemento('selector-fondo-tarea', HTMLElement),
	opcionesDeFondo: exigirElemento('selector-fondo-opciones', HTMLElement),
	filtros: exigirElemento('filtros', HTMLElement),
	campoBusqueda: exigirElemento('campo-busqueda', HTMLInputElement),
	radiosDeFiltro: document.querySelectorAll('.filtros__radio'),
	sinResultados: exigirElemento('sin-resultados', HTMLElement),
	avisoArrastre: exigirElemento('aviso-arrastre', HTMLElement),
	atajos: exigirElemento('atajos', HTMLDialogElement),
	botonAtajos: exigirElemento('boton-atajos', HTMLButtonElement)
};

/** Se exponen para que `app.js` y `arrastre.js` cuelguen sus listeners sin volver a buscarlos. */
export const lista = elementos.lista;

export const campoTexto = elementos.campoTexto;

export const campoBusqueda = elementos.campoBusqueda;

/**
 * Bloquea el botón de añadir mientras va la petición.
 *
 * Sin esto, pulsar Enter dos veces seguidas manda dos POST y crea la tarea por duplicado. Aquí no
 * vale la interfaz optimista: el id lo asigna el servidor, así que hay que esperar su respuesta.
 *
 * @param {boolean} bloqueado
 */
export function bloquearAlta(bloqueado) {
	elementos.botonAnadir.disabled = bloqueado;
}

/** Lo fija el servidor al arrancar; hasta entonces no se limita nada por el lado del navegador. */
/** @type {number | null} */
let maxCaracteresTexto = null;

/**
 * Aplica el límite de caracteres que dicta el servidor.
 *
 * El número no está escrito en el HTML ni en este archivo a propósito: la única fuente de verdad es
 * `Tarea.MAX_CARACTERES_TEXTO` en Java, y llega por el endpoint de configuración.
 *
 * @param {number} maximo
 */
export function configurarLimiteDeTexto(maximo) {
	maxCaracteresTexto = maximo;
	elementos.campoTexto.maxLength = maximo;
	const editores = /** @type {NodeListOf<HTMLTextAreaElement>} */
		(elementos.lista.querySelectorAll('.tarea__editor'));
	for (const editor of editores) {
		editor.maxLength = maximo;
	}
	actualizarContador();
}

/* --------------------------------------------------------------- Pintar */

/** Reconstruye la lista completa. Solo para cambios estructurales. */
export function pintarLista() {
	conTransicion(reconstruirLista);
}

/**
 * Anima el cambio si el navegador sabe hacerlo, y si no lo aplica de golpe.
 *
 * `pintarLista()` reconstruye la lista entera con `replaceChildren`, así que hasta ahora añadir o
 * borrar una tarea hacía parpadear todas las tarjetas y pegar un salto a lo que había debajo, sin
 * ninguna transición. La View Transitions API resuelve justo eso: el navegador fotografía el antes y
 * el después y anima la diferencia él solo, incluidas las tarjetas que se mueven de sitio.
 *
 * Es una mejora progresiva. En un navegador que no la traiga, `startViewTransition` no existe y el
 * cambio se aplica igual, solo que sin animar; nada depende de ella para funcionar. Y si el usuario
 * ha pedido menos movimiento, ni se intenta: la hoja de estilos también la desactiva, pero saltarla
 * aquí evita el trabajo de capturar las fotos para luego no usarlas.
 *
 * @param {() => void} cambiarElDom
 */
function conTransicion(cambiarElDom) {
	const prefiereMenosMovimiento = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
	const puedeAnimar = typeof document.startViewTransition === 'function' && !prefiereMenosMovimiento;

	if (puedeAnimar) {
		document.startViewTransition(cambiarElDom);
	}
	else {
		cambiarElDom();
	}
}

function reconstruirLista() {
	const todas = estado.obtenerTareas();
	const visibles = estado.obtenerTareasVisibles();
	const idEnEdicion = estado.obtenerIdEnEdicion();

	const fragmento = document.createDocumentFragment();
	for (const tarea of visibles) {
		fragmento.appendChild(construirTarjeta(tarea, idEnEdicion === tarea.id));
	}

	elementos.lista.replaceChildren(fragmento);

	// Tres estados distintos que es importante no confundir: no hay ninguna tarea, hay tareas pero
	// el filtro no encuentra ninguna, o hay resultados.
	elementos.listaVacia.hidden = todas.length > 0;
	elementos.filtros.hidden = todas.length === 0;
	actualizarSinResultados(todas.length, visibles.length);
	actualizarAvisoDeArrastre();

	actualizarBotonesVerMas();
	actualizarResumen();
	enfocarEditorEnEdicion();
}

/**
 * El mensaje de «no hay resultados», que no es lo mismo que «no hay tareas».
 *
 * @param {number} cuantasHay
 * @param {number} cuantasSeVen
 */
function actualizarSinResultados(cuantasHay, cuantasSeVen) {
	const sobranTareasPeroNoSeVeNinguna = cuantasHay > 0 && cuantasSeVen === 0;
	elementos.sinResultados.hidden = !sobranTareasPeroNoSeVeNinguna;
	if (sobranTareasPeroNoSeVeNinguna) {
		elementos.sinResultados.textContent = mensajeDeSinResultados();
	}
}

function mensajeDeSinResultados() {
	const { estado: cual, busqueda } = estado.obtenerFiltro();

	let resultado;
	if (busqueda !== '') {
		resultado = `Ninguna tarea coincide con «${busqueda}».`;
	}
	else if (cual === 'pendientes') {
		resultado = 'No queda ninguna tarea pendiente.';
	}
	else {
		resultado = 'Todavía no has completado ninguna tarea.';
	}
	return resultado;
}

/** Con un filtro puesto no se puede reordenar, y conviene decirlo en vez de que el asa no responda. */
function actualizarAvisoDeArrastre() {
	const hayAlgoQueOrdenar = estado.obtenerTareas().length > 1;
	elementos.avisoArrastre.hidden = !(estado.hayFiltroActivo() && hayAlgoQueOrdenar);
}

/** Cuántas tareas se están viendo, para anunciarlo a quien no ve la lista. */
export function describirResultados() {
	const visibles = estado.obtenerTareasVisibles().length;
    const total = estado.obtenerTareas().length;

	let resultado;
	if (!estado.hayFiltroActivo()) {
		resultado = `${total} ${total === 1 ? 'tarea' : 'tareas'}.`;
	}
	else if (visibles === 0) {
		resultado = mensajeDeSinResultados();
	}
	else {
		resultado = `${visibles} de ${total} ${total === 1 ? 'tarea' : 'tareas'}.`;
	}
	return resultado;
}

/** @param {(filtro: {estado: string, busqueda: string}) => void} manejador */
export function alCambiarElFiltro(manejador) {
	elementos.campoBusqueda.addEventListener('input', () => {
		manejador({ estado: estadoSeleccionado(), busqueda: elementos.campoBusqueda.value });
	});
	for (const radio of elementos.radiosDeFiltro) {
		radio.addEventListener('change', () => {
			manejador({ estado: estadoSeleccionado(), busqueda: elementos.campoBusqueda.value });
		});
	}
}

function estadoSeleccionado() {
	const marcado = /** @type {HTMLInputElement | null} */
		(document.querySelector('.filtros__radio:checked'));
	const resultado = marcado?.value ?? 'todas';
	return resultado;
}

/**
 * Actualiza una sola tarjeta sin tocar las demás.
 *
 * Es lo que se usa al marcar completada, desplegar el texto o entrar y salir de edición: cambios
 * que no alteran ni cuántas tareas hay ni en qué orden están.
 *
 * @param {number} id
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
 *
 * @param {number} desde
 * @param {number} hasta
 */
export function moverTarjeta(desde, hasta) {
	const tarjeta = elementos.lista.children[desde];
	if (!tarjeta) {
		return;
	}
	// Al mover hacia abajo, el nodo de destino se «corre» una posición en cuanto se saca el actual,
	// así que la referencia es el siguiente.
	let posicionDeReferencia = hasta;
	if (hasta > desde) {
		posicionDeReferencia = hasta + 1;
	}
	const referencia = elementos.lista.children[posicionDeReferencia] ?? null;
	elementos.lista.insertBefore(tarjeta, referencia);
}

/**
 * @param {import('./tipos.js').Tarea} tarea
 * @param {boolean} enEdicion
 * @returns {HTMLLIElement}
 */
function construirTarjeta(tarea, enEdicion) {
	const modelo = exigirDentro(elementos.plantilla.content, '.tarea', HTMLLIElement);
	const resultado = /** @type {HTMLLIElement} */ (modelo.cloneNode(true));
	resultado.dataset.id = String(tarea.id);

	// El nombre tiene que ser único y estable por tarea: es lo que permite al navegador emparejar
	// cada tarjeta con la misma de antes y animar el movimiento en vez de un cambio brusco.
	resultado.style.viewTransitionName = `tarea-${tarea.id}`;
	rellenarTarjeta(resultado, tarea, enEdicion);
	return resultado;
}

/**
 * Vuelca una tarea sobre una tarjeta, exista ya o acabe de clonarse.
 *
 * Se reparte en cuatro pasos con nombre en lugar de escribirlo todo seguido: cada uno toca una zona
 * distinta de la tarjeta, y así se puede leer solo el que interesa sin tener que recorrer el resto.
 *
 * @param {HTMLLIElement} tarjeta
 * @param {import('./tipos.js').Tarea} tarea
 * @param {boolean} enEdicion
 */
function rellenarTarjeta(tarjeta, tarea, enEdicion) {
	tarjeta.classList.toggle('tarea--completada', tarea.completada);
	tarjeta.classList.toggle('tarea--desplegada', estado.estaDesplegada(tarea.id));
	aplicarFondo(tarjeta, estado.obtenerFondoDe(tarea.id));

	rellenarCasilla(tarjeta, tarea);
	nombrarLosBotones(tarjeta, tarea);
	rellenarTextoYEditor(tarjeta, tarea);
	aplicarModoEdicion(tarjeta, enEdicion);
}

/** La casilla de completada y su etiqueta, que va oculta pero es la que la nombra. *
 * @param {HTMLLIElement} tarjeta
 * @param {import('./tipos.js').Tarea} tarea
 */
function rellenarCasilla(tarjeta, tarea) {
	const casilla = exigirDentro(tarjeta, '.tarea__casilla', HTMLInputElement);
	const etiqueta = exigirDentro(tarjeta, '.tarea__control-completada label', HTMLLabelElement);

	casilla.checked = tarea.completada;
	casilla.id = `casilla-${tarea.id}`;
	etiqueta.htmlFor = casilla.id;

	if (tarea.completada) {
		etiqueta.textContent = `Marcar «${tarea.texto}» como pendiente`;
	}
	else {
		etiqueta.textContent = `Marcar «${tarea.texto}» como completada`;
	}
}

/**
 * Cada botón dice de qué tarea es.
 *
 * Sin esto, un lector de pantalla recorre la lista diciendo «Editar, botón», «Borrar, botón»… sin
 * nombrar nunca la tarea a la que pertenecen.
 *
 * @param {HTMLLIElement} tarjeta
 * @param {import('./tipos.js').Tarea} tarea
 */
function nombrarLosBotones(tarjeta, tarea) {
	const nombresPorSelector = {
		'.tarea__asa': `Mover la tarea «${tarea.texto}»`,
		'[data-accion="editar"]': `Editar la tarea «${tarea.texto}»`,
		'[data-accion="eliminar"]': `Borrar la tarea «${tarea.texto}»`,
		'[data-accion="fondo"]': `Elegir el fondo de la tarea «${tarea.texto}»`
	};

	for (const [selector, nombre] of Object.entries(nombresPorSelector)) {
		exigirDentro(tarjeta, selector, Element).setAttribute('aria-label', nombre);
	}
}

/** El párrafo que se ve y el cuadro de edición que hay debajo. *
 * @param {HTMLLIElement} tarjeta
 * @param {import('./tipos.js').Tarea} tarea
 */
function rellenarTextoYEditor(tarjeta, tarea) {
	exigirDentro(tarjeta, '.tarea__texto', HTMLElement).textContent = tarea.texto;

	const editor = exigirDentro(tarjeta, '.tarea__editor', HTMLTextAreaElement);
	editor.setAttribute('aria-label', 'Editar el texto de la tarea');
	if (maxCaracteresTexto !== null) {
		editor.maxLength = maxCaracteresTexto;
	}

	// Si el usuario está escribiendo AHÍ, pisarle el valor le movería el cursor y le borraría lo que
	// acabase de teclear. Su texto es más reciente que el del estado.
	const loEstaEscribiendoAhora = document.activeElement === editor;
	if (editor.value !== tarea.texto && !loEstaEscribiendoAhora) {
		editor.value = tarea.texto;
	}
}

/** Alterna entre ver el texto y editarlo. Es lo único que cambia al entrar y salir de la edición. *
 * @param {HTMLLIElement} tarjeta
 * @param {boolean} enEdicion
 */
function aplicarModoEdicion(tarjeta, enEdicion) {
	exigirDentro(tarjeta, '.tarea__texto', HTMLElement).hidden = enEdicion;
	exigirDentro(tarjeta, '.tarea__editor', HTMLElement).hidden = !enEdicion;

	const textoDelBoton = exigirDentro(tarjeta, '[data-accion="editar"] .tarea__accion-texto', HTMLElement);
	if (enEdicion) {
		textoDelBoton.textContent = 'Listo';
	}
	else {
		textoDelBoton.textContent = 'Editar';
	}
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

/** Pasada de LECTURA: solo consulta el DOM, no lo modifica. *
 * @param {Element} tarjeta
 * @returns {boolean}
 */
function medirDesbordamiento(tarjeta) {
	const parrafo = exigirDentro(tarjeta, '.tarea__texto', HTMLElement);

	let resultado;
	if (parrafo.hidden) {
		// Mientras se edita, el párrafo no se pinta y sus medidas son cero: no hay nada que recortar.
		resultado = false;
	}
	else {
		resultado = parrafo.scrollHeight > parrafo.clientHeight + 1;
	}
	return resultado;
}

/**
 * Pasada de ESCRITURA: solo modifica el DOM, no lo consulta.
 *
 * Son tres casos excluyentes y se escriben como tales, con if/else, en vez de con tres salidas
 * sueltas: así se ve de un vistazo que uno y solo uno se aplica siempre.
 *
 * @param {Element} tarjeta
 * @param {boolean} desborda
 */
function actualizarBotonVerMasDe(tarjeta, desborda) {
	const boton = exigirDentro(tarjeta, '.tarea__boton-desplegar', HTMLElement);
	const parrafo = exigirDentro(tarjeta, '.tarea__texto', HTMLElement);

	if (parrafo.hidden) {
		// Se está editando: el botón de desplegar no pinta nada ahí.
		boton.hidden = true;
	}
	else if (estado.estaDesplegada(Number(/** @type {HTMLElement} */ (tarjeta).dataset.id))) {
		// Ya desplegada: al no haber recorte no se puede medir el desbordamiento, pero el botón
		// tiene que seguir ahí para poder volver a plegarla.
		boton.hidden = false;
		boton.textContent = 'Ver menos';
		boton.setAttribute('aria-expanded', 'true');
	}
	else {
		boton.hidden = !desborda;
		boton.textContent = 'Ver más';
		boton.setAttribute('aria-expanded', 'false');
	}
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

/**
 * @param {number} id
 * @returns {HTMLTextAreaElement | null}
 */
function obtenerEditorDeTarea(id) {
	const editor = elementos.lista.querySelector(`[data-id="${id}"] .tarea__editor`);
	const resultado = (editor instanceof HTMLTextAreaElement) ? editor : null;
	return resultado;
}

/**
 * @param {number} id
 * @returns {HTMLLIElement | null}
 */
export function obtenerTarjetaDeTarea(id) {
	const tarjeta = elementos.lista.querySelector(`[data-id="${id}"]`);
	const resultado = (tarjeta instanceof HTMLLIElement) ? tarjeta : null;
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

	if (tareas.length === 0) {
		elementos.resumen.textContent = 'Sin tareas.';
	}
	else {
		elementos.resumen.textContent = `${completadas} de ${tareas.length} completadas.`;
	}

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
	{ valor: estado.SIN_FONDO, nombre: 'Sin fondo' },
	{ valor: 'ondas', nombre: 'Ondas' },
	{ valor: 'puntos', nombre: 'Puntos' },
	{ valor: 'lineas', nombre: 'Líneas' },
	{ valor: 'papel', nombre: 'Papel' },
	{ valor: 'aurora', nombre: 'Aurora' }
];

/**
 * @param {HTMLLIElement} tarjeta
 * @param {string} fondo
 */
function aplicarFondo(tarjeta, fondo) {
	const llevaFondo = fondo !== estado.SIN_FONDO;

	// «Sin fondo» se salta: no tiene clase propia, es la ausencia de todas las demás. Antes entraba
	// en el bucle solo para apagar una clase que no existe en la hoja de estilos.
	for (const opcion of FONDOS) {
		if (opcion.valor !== estado.SIN_FONDO) {
			tarjeta.classList.toggle(`tarea--fondo-${opcion.valor}`, llevaFondo && opcion.valor === fondo);
		}
	}
	tarjeta.classList.toggle('tarea--con-fondo', llevaFondo);
}

/**
 * Abre el selector de fondo de una tarea.
 *
 * <p>Se usa `showModal()` y no `show()`: es lo que atrapa el foco dentro del diálogo, oscurece el
 * resto y habilita el cierre con Escape. Al cerrarse, el navegador devuelve el foco al botón que lo
 * abrió sin que haya que guardarlo a mano.
 *
 * @param {import('./tipos.js').Tarea} tarea
 * @param {string} fondoActual
 * @param {(fondoElegido: string) => void} alElegir
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
		if (opcion.valor !== estado.SIN_FONDO) {
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

/**
 * Abre o cierra la ayuda de atajos.
 *
 * Se alterna en vez de solo abrir para que la misma tecla que la saca la guarde, que es lo que
 * espera quien la ha abierto sin querer.
 */
export function alternarAtajos() {
	if (elementos.atajos.open) {
		elementos.atajos.close();
	}
	else {
		elementos.atajos.showModal();
	}
}

/** @param {() => void} manejador */
export function alPulsarVerAtajos(manejador) {
	elementos.botonAtajos.addEventListener('click', manejador);
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
 *
 * @param {string} tema
 */
export function aplicarTema(tema) {
	if (tema === 'claro' || tema === 'oscuro') {
		document.documentElement.dataset.tema = tema;
	}
	else {
		delete document.documentElement.dataset.tema;
	}

	for (const radio of elementos.radiosDeTema) {
		if (radio instanceof HTMLInputElement) {
			radio.checked = (radio.value === tema);
		}
	}
}

/** @param {(tema: string) => void} manejador */
export function alCambiarElTema(manejador) {
	for (const radio of elementos.radiosDeTema) {
		if (radio instanceof HTMLInputElement) {
			radio.addEventListener('change', () => manejador(radio.value));
		}
	}
}

/* ------------------------------------------------------- Aviso de deshacer */

/**
 * @param {string} textoDeLaTarea
 * @param {() => void} alPulsarDeshacer
 */
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
/** @param {number} id */
export function enfocarTarjeta(id) {
	const casilla = obtenerTarjetaDeTarea(id)?.querySelector('.tarea__casilla');
	if (casilla instanceof HTMLElement) {
		casilla.focus();
	}
}

export function ocultarDeshacer() {
	elementos.avisoDeshacer.hidden = true;
	elementos.botonDeshacer.onclick = null;
}

/* --------------------------------------------------------------------- Avisos */

/** Único punto donde se muestra un error al usuario, sea de red, de validación o del servidor. */
/** @param {string} mensaje */
export function mostrarError(mensaje) {
	elementos.avisoError.textContent = mensaje;
	elementos.avisoError.hidden = false;
}

export function limpiarError() {
	elementos.avisoError.textContent = '';
	elementos.avisoError.hidden = true;
}

/** Mensaje solo para lectores de pantalla: cambios que se ven pero no se «oyen». */
/** @param {string} mensaje */
export function anunciar(mensaje) {
	elementos.anuncios.textContent = mensaje;
}

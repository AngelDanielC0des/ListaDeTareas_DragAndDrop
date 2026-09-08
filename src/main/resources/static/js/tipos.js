/**
 * Tipos compartidos por los módulos del frontend.
 *
 * Este archivo no se carga en el navegador: no exporta nada ejecutable y nadie lo importa. Existe
 * solo para que `tsc --checkJs` tenga un sitio donde estén declaradas de una vez las formas que
 * viajan entre el servidor y la interfaz, en vez de repetirlas en cada JSDoc.
 *
 * Se referencian desde otro módulo con `import('./tipos.js').Tarea`, que TypeScript entiende dentro
 * de un comentario y el navegador nunca llega a ver.
 */

/**
 * Una tarea, tal y como la manda y la recibe el servidor.
 *
 * Son exactamente tres campos, y esa es una restricción del proyecto, no una casualidad: el orden
 * lo da la posición en el array y el fondo vive en un mapa aparte.
 *
 * @typedef {object} Tarea
 * @property {number} id identidad, no cambia nunca ni al reordenar
 * @property {string} texto lo que escribió el usuario
 * @property {boolean} completada
 */

/**
 * Límites que dicta el servidor y que el navegador pide al arrancar.
 *
 * @typedef {object} ConfiguracionTareas
 * @property {number} maxCaracteresTexto
 */

/**
 * Un grupo bajo el que se agrupan tareas.
 *
 * El grupo no es un campo de la tarea y no puede serlo: una tarea son exactamente tres campos. La
 * pertenencia viaja aparte, en el mapa de asignaciones.
 *
 * @typedef {object} Grupo
 * @property {number} id identidad del grupo; no cambia al reordenar
 * @property {string} nombre lo que se enseña en la cabecera de la sección
 */

/**
 * Los grupos y a qué grupo pertenece cada tarea, tal y como los manda el servidor.
 *
 * Las claves de `asignaciones` llegan como cadenas porque un objeto JSON no tiene claves numéricas,
 * pero se consultan con el id numérico: JavaScript convierte el índice por su cuenta.
 *
 * @typedef {object} GruposConAsignaciones
 * @property {Grupo[]} grupos en el orden en que deben pintarse
 * @property {Record<string, number>} asignaciones id de tarea al id de su grupo
 */

/**
 * Qué fondo tiene cada tarea, indexado por id. Solo aparecen las que tienen uno.
 *
 * Las claves llegan como cadenas porque un objeto JSON no tiene claves numéricas, pero se consultan
 * con el id numérico: JavaScript convierte el índice a cadena por su cuenta.
 *
 * @typedef {Record<string, string>} MapaDeFondos
 */

export {};

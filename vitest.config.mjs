import { defineConfig } from 'vitest/config';

/**
 * Configuración de las pruebas del frontend.
 *
 * Las pruebas viven en `src/test/js/` y NO en `src/main/resources/static/js/`, que es donde están
 * los módulos. No es una manía de orden: todo lo que hay bajo `static/` lo sirve Spring y acaba
 * dentro del jar, así que un archivo de pruebas ahí se publicaría en internet.
 *
 * El entorno por defecto es `node` porque tres de los cuatro módulos que se prueban son lógica pura
 * y montar un DOM para ellos sería trabajo tirado. El único que lo necesita, `vista.js`, lo pide en
 * su propio archivo con el comentario `@vitest-environment jsdom`.
 */
export default defineConfig({
	test: {
		include: ['src/test/js/**/*.test.js'],
		environment: 'node',
		restoreMocks: true
	}
});

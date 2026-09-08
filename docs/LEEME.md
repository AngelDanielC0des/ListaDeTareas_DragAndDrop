# Capturas y GIF que faltan

El README enlaza tres archivos que **todavía no existen**, porque hacen falta un navegador y una
grabación de pantalla:

| Archivo | Qué debe enseñar |
|---|---|
| `arrastre.gif` | 3-5 s arrastrando una tarjeta por el asa `⠿` hasta otra posición. Es lo primero que se ve del proyecto: que se entienda el gesto y que el hueco se abre solo. |
| `claro.png` | La lista con 4-5 tareas, alguna completada y alguna con fondo, en tema claro. |
| `oscuro.png` | Lo mismo en tema oscuro, para que se vea que el tema no es un filtro por encima. |

## Cómo grabarlas

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=demo
```

El perfil `demo` siembra cinco tareas de ejemplo, dos con fondo y una completada, así que la pantalla
ya sale poblada sin tener que escribir nada.

Para el GIF sirve **ScreenToGif** (gratuito, en Windows). Recorta a la zona de la lista, no grabes la
pantalla entera: en la miniatura de GitHub se vería diminuto.

Ancho recomendado: **entre 900 y 1200 px**. Más grande solo hace el repositorio más pesado.

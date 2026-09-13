# Empezá por acá

Estos archivos existen para que una IA que abre este repo por primera vez sepa qué es, qué NO es, y
dónde están las minas. El `CLAUDE.md` de la raíz se carga solo y es el resumen; esto es el detalle.

| archivo | para qué |
|---|---|
| [orientacion.md](orientacion.md) | Qué es la app, de dónde saca el video, cómo está organizado el código |
| [reglas.md](reglas.md) | Las no negociables: sin servidor propio, idioma, identidad de los commits |
| [trampas.md](trampas.md) | **Lo más valioso.** Cosas medidas que costaron horas o días descubrir |
| [como-trabajar.md](como-trabajar.md) | Compilar, instalar, probar en el celu y en el TV, y cómo verificar de verdad |

## Lo mínimo, si no vas a leer nada más

1. **Falta el `.env` y no te vas a enterar.** No está en git (nunca estuvo). Sin él la app compila,
   instala y no sirve: catálogo vacío, Magis sin autenticar. Ningún error. `cp .env.example .env`
   y llenalo.
2. **`docs/` está mayormente podrido.** 108 de sus 153 archivos hablan de torrent, 43 de PocketBase,
   25 de libVLC — subsistemas que esta app **borró**. Ver [trampas.md](trampas.md#docs-podrido).
3. **`lordmacu/arkiv` es otro codebase, no una versión vieja de este.** Leer código de allá para
   arreglar un bug de acá produce una respuesta segura e incorrecta.
4. **Verificá ejecutando, no leyendo.** En este proyecto los tests verdes y los informes mienten.
   Ver [como-trabajar.md](como-trabajar.md#verificar).

# Rendimiento · M25

La prueba del usuario confirma que Steam abre y permite iniciar sesión en Winlator 11.2, pero la interacción resulta muy lenta. Ese dato cambia el objetivo: conservar la compatibilidad de la base y reducir el trabajo añadido por DroidDeck. Abrir el cliente y jugar con fluidez son resultados distintos y deben medirse por separado.

## Coste encontrado en código

RuntimeConsoleOverlay seguía suscrito después de ocultarse al aparecer Steam. Cada cambio de logs podía reconstruir el historial completo y asignarlo a un TextView invisible, con layout y scroll, hasta cuatro veces por segundo. El launcher también permanecía suscrito en segundo plano si Diagnóstico estaba abierto. La cola conservaba hasta 900 mensajes, sin presupuesto de caracteres, y el callback runtime formateaba y escribía todos los mensajes filtrados, incluso los repetidos.

## Cambios

- La consola runtime se desconecta tanto al aparecer Steam como al abrir manualmente la pantalla del motor. Ignora callbacks ya encolados y telemetría visual cuando se ocultó manualmente.
- El launcher se desconecta en onPause y vuelve a suscribirse en onResume solo si Diagnóstico está visible.
- ConsoleLogStore no programa refrescos ni reconstruye snapshots para un conjunto vacío de observadores. Conserva el registro en disco y el callback del proceso para diagnóstico.
- Historial visual limitado a 65.536 caracteres y 900 líneas; mensaje individual limitado a 16.384 caracteres. El archivo de sesión sigue siendo la fuente de diagnóstico detallado; la copia visible es una cola.
- Modo normal: conserva el primer mensaje de cada intervalo, agrupa repeticiones consecutivas y limita a 40 registros runtime por segundo. Informa de cuántos mensajes se agruparon. ERROR/WARN del controlador no pasan por ese límite.
- Registro detallado: omite el throttle runtime; mantiene el truncado de 600 caracteres por línea existente desde M22. Está destinado a diagnóstico, porque aumenta CPU y escritura.

Se mantienen la base Winlator 11.2, perfil Performance, argumentos de Steam, drivers, resolución, archivos del cliente y datos del usuario. No se introducen cambios no probados en CEF o sincronización Box64.

## Evidencia y límite de la mejora

La prueba determinista de una ráfaga de 10.000 mensajes distintos en un intervalo conserva 40 registros y resume 9.960: un 99,6 % menos de registros que formatear y escribir en ese escenario. Diez mil repeticiones conservan el primer error y un resumen. La prueba incluye intervalo siguiente, flush, reset y reloj que retrocede.

Android 35 ejecuta el código real de la consola: una ráfaga de logs tras ocultarla manualmente y tras simular ready produce cero cambios de texto en el registro oculto, incluso con callbacks tardíos. Se comprueba el presupuesto visual y que el modo detallado conserve cien mensajes únicos. Las pruebas M24 de procesos reales, UI a diez minutos, JNI y firma/inspección ARM64 se mantienen.

Esto demuestra menos trabajo de nuestra capa de logs/UI. **No significa Steam un 99,6 % más rápido**, ni acredita FPS, ausencia de tirones o menor tiempo de apertura en Mali. Wine/Box64, CEF, GPU, presión de RAM y temperatura pueden seguir siendo el cuello de botella. El emulador x86_64 no reproduce el motor ARM64 del teléfono.

## Medición pendiente en teléfono

Con la misma versión del cliente y ajustes, comparar Winlator 11.2 y DroidDeck: inicio de sesión, tiempo hasta biblioteca utilizable, navegación/scroll y juego concreto. Separar CPU/PSS de launcher, steam.exe y steamwebhelper.exe; anotar RAM física, SoC/GPU, presión de memoria y temperatura. Probar primero sin registro detallado y capturar un intervalo corto si hay bloqueo. Los cambios de renderizado CEF, resolución o perfiles deben validarse con esta medición antes de adoptarlos como predeterminados.

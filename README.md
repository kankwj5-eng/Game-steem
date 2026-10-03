# Game-steem · DroidDeck M2

Launcher Android de estilo consola construido sobre el motor de **Winlator 11.2**, manteniendo Wine, Box64, XServer, audio, entrada y las rutas gráficas existentes, pero ocultando la interfaz técnica al usuario normal.

## Objetivo

La experiencia visible debe ser simple:

1. abrir DroidDeck;
2. conceder almacenamiento cuando Android lo requiera;
3. ver en pantalla qué se está preparando;
4. detectar GPU y elegir la ruta gráfica automáticamente;
5. crear el entorno Wine/Box64;
6. descargar Steam desde el CDN oficial;
7. instalar Steam silenciosamente;
8. iniciar sesión en Steam y jugar.

La interfaz de Winlator queda como backend técnico y no es el launcher principal.

## M2

- interfaz horizontal moderna, en español y orientada a mando/táctil;
- panel de preparación con estados, progreso y errores;
- consola de diagnóstico que se abre automáticamente ante fallos;
- registro persistente por sesión dentro del almacenamiento privado de la app;
- solicitud de permisos de almacenamiento en Android 12L o inferior;
- `requestLegacyExternalStorage` mientras la base upstream continúe en `targetSdk 28`;
- descarga reanudable de `SteamSetup.exe`, tres intentos y validación PE `MZ`;
- instalación silenciosa con `SteamSetup.exe /S`;
- soporte explícito de `exec_args` añadido al lanzador Winlator;
- re-detección y arranque automático de Steam después de instalar;
- detección de GPU mediante `GPUHelper` y selección upstream Turnip/Vortek;
- `save_mem_on_run_from_steam` habilitado;
- logs Wine `warn,err,fixme` y Box64 en nivel de diagnóstico;
- aplicación separada de Winlator original: `com.droiddeck.console`.

## Base fijada

`brunodev85/winlator-app`

Commit:

`3981d86efa4f333b2a34a7da8b6521476cd8c8b9`

No se sigue `main` automáticamente: actualizar Winlator requiere una auditoría explícita del overlay.

## Compilación

GitHub Actions ejecuta `.github/workflows/build-droiddeck.yml` en cada push a `main` y también manualmente. El artefacto esperado es:

`Game-steem-DroidDeck-M2-debug.apk`

El workflow valida además que el APK final tenga el package `com.droiddeck.console`.

## Estado

M2 es una compilación de integración. Hasta instalar el APK en hardware real Mali y ejecutar Steam no se considera una versión estable. Los fallos de compilación y de runtime deben conservarse porque alimentan la siguiente ronda de correcciones.

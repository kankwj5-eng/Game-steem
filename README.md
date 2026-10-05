# DroidDeck · Steam para Android

Launcher Android en español, orientado únicamente a Steam, sobre una base fijada de Winlator 11.2. Wine, Box64, XServer, audio y controladores gráficos forman el motor; la pantalla principal presenta preparación, progreso y arranque de Steam.

## Instalación automática

Steam Legacy procede de Winlator Addons, no de SteamSetup.exe. El paquete se valida mediante tamaño oficial y SHA-256, con reanudación HTTP Range, control de Content-Range, manejo de HTTP 416 y cuatro intentos con backoff.

M21 extrae en staging, verifica los tamaños de todos los archivos y confirma el marcador `.droiddeck-source` al terminar. El marcador identifica la fuente y el SHA esperado; seis ejecutables y bibliotecas principales se comprueban antes de habilitar el arranque. Una extracción completa pendiente se activa al reiniciar. La carpeta anterior se conserva como `.droiddeck-steam-previous` (con sufijo si ya existe), por si contiene juegos o partidas; no se elimina automáticamente. Una instalación válida no se reemplaza. Los recibos válidos de M20 siguen siendo compatibles.

Los archivos y directorios se sincronizan antes de confirmar la instalación para reducir el riesgo por apagados. Esto añade coste a la primera extracción; el arranque habitual solo comprueba el marcador y seis binarios. Tras una instalación verificada se elimina la caché del paquete.

## Arranque y pantalla · M26

- M26 recupera procesos del entorno Steam marcados por la app al iniciar y cerrar; conserva los procesos sin marcador, incluido el instalador. Registra la RAM disponible y las restricciones de procesos hijos sin cambiar ajustes del sistema. [Recuperación y límites M26](docs/recuperacion-procesos-m26.md).
- La consola se desconecta al ocultarse; el launcher deja de recibir logs en segundo plano. El historial visual se limita a 64 Ki caracteres y el modo normal agrupa repetidos con presupuesto de 40 líneas/s; el detalle opcional conserva los registros. [Pruebas y límites de rendimiento M25](docs/rendimiento-m25.md).
- Arranque observado con errores y salida real; monitor de CPU y ventanas con metadatos tardíos. La espera permite ver el motor, volver y compartir el registro. Detalles y límites en [auditoría M24](docs/auditoria-arranque-steam-m24.md).
- Extracción nativa C de 7-Zip: evita el diccionario de 256 MiB en Java que causaba el error de memoria. El paquete sólido aún necesita aproximadamente 527 MiB temporales nativos en la prueba local; se liberan al terminar. Fuente fijada en `native-dependencies.lock`.
- Errores breves en español, porcentaje oculto al fallar y columna principal desplazable para alcanzar los botones en pantallas horizontales pequeñas.
- Perfil Box64 de rendimiento, servicios esenciales y ahorro de memoria de Steam.
- Wine conserva errores; los logs detallados de Wine/Box64 son opcionales desde Diagnóstico para el próximo inicio.
- Un único observador filtrado registra mensajes runtime antes y después de la primera ventana, conservando batching y refresco a 250 ms.
- El monitor comprueba procesos fuera del hilo de interfaz cada dos segundos y actividad de carpetas mutables cada cinco segundos, con límite de 1.024 archivos. La telemetría de disco es una muestra, no una medición de todos los juegos.
- La prueba de caché comprueba tamaño y firma 7z sin indexar previamente todo el paquete; SHA e índice real siguen verificándose antes de extraer.
- Pantalla principal con estados de preparación; registro oculto hasta solicitarlo y visible automáticamente ante errores.

El objetivo son dispositivos con al menos 4 GB de RAM. Esto no garantiza compatibilidad con todos los juegos ni con todas las GPU. La ruta Mali y el tiempo real de apertura de Steam necesitan validación en hardware.

## Organización

| Ruta | Responsabilidad |
|---|---|
| `overlay/app/src/main/java/com/winlator/console/` | Bootstrap, instalación, recuperación, monitor y logs |
| `overlay/app/src/main/res/` | Interfaz y recursos |
| `tools/apply_overlay.py` | Integración sobre la base fijada |
| `tools/check_overlay.py` | Contratos y comprobaciones del overlay |
| `overlay/app/src/main/cpp/steamarchive/` | Decoder JNI y presupuesto de memoria |
| `tools/tests/SteamInstallationTest.java` | Escenarios de instalación interrumpida |
| `.github/workflows/build-droiddeck.yml` | Build, firma, APK, contrato Steam y análisis |

## Base y validación

Base: `brunodev85/winlator-app`, commit `3981d86efa4f333b2a34a7da8b6521476cd8c8b9`. Actualizar la base requiere auditar el overlay.

```sh
python3 tools/check_overlay.py
bash tools/test_steam_installation.sh  # JDK 17
bash tools/test_runtime_startup.sh
bash tools/prepare_effective_source.sh
```

Actions ejecuta los controles en PR y en main: Gradle assembleDebug, firma de desarrollo estable, inspección de package `com.droiddeck.console`, contrato real de Steam Legacy, extracción con Java limitado a 64 MiB, prueba Android de JNI y pantalla pequeña, y análisis. El artefacto M25 se llama `Game-steem-DroidDeck-M25-debug.apk`.

La clave de desarrollo pública permite actualizar entre builds, pero no es una clave privada de publicación. Un build verde verifica compilación y empaquetado; no sustituye una prueba de Steam y juegos en un teléfono real.

La investigación de alternativas, resultados y límites de las pruebas están en [viabilidad Steam en Android](docs/viabilidad-steam-android.md).

# Auditoría de arranque · M24

La captura muestra diez minutos de espera de la primera ventana después de iniciar el motor. No demuestra por sí sola una falta de RAM, fallo CEF o fallo de GPU. El error de asignación Java del instalador corresponde a otro punto del flujo y se corrigió en M23.

## Comparación con Winlator 11.2

Se revisó la base fijada `3981d86efa4f333b2a34a7da8b6521476cd8c8b9`: `Container`, `Box64Preset`, `GuestProgramLauncherComponent`, `XServerDisplayActivity`, `WinHandler`, `ProcessHelper` y `assets/box64/default.box64rc`.

El perfil Performance también es el predeterminado de Container en esa base. El bloque `[steam.exe]`, sus argumentos WINEARGS y el bloque `[steamwebhelper.exe]` permanecen sin cambios. La ruta de lanzamiento sigue siendo Wine explorer → winhandler → steam.exe, como en la base. No se añade Termux ni otro motor al camino crítico. Que Steam abra en Winlator en un teléfono es una referencia útil; no acredita aún que este fork use exactamente la misma versión del cliente, prefijo y ajustes del dispositivo.

## Defectos concretos corregidos

| Defecto | Corrección |
|---|---|
| Excepciones al crear procesos se ignoraban; envVars nulo causaba excepción | Arranque observado, error visible y una notificación de finalización |
| Fallo de reflexión PID impedía conectar lectores y callback después de arrancar | Lectores y espera se conectan primero; si no se obtiene PID, se cancela el proceso |
| Cada lector/espera creaba un ejecutor que quedaba vivo | Hilos con vida limitada a EOF/finalización; prueba repetida de fugas |
| Un PID inaccesible devolvía una lista vacía completa | Se omite esa fila; se conservan filas válidas y se filtra UID de la app |
| Detección de primera ventana solo en MAP y cualquier clase | Reevalúa contenido, geometría y propiedades; exige ventana renderizable de Steam y excluye el escritorio |
| Monitor confundía ausencia de red/disco con bloqueo aunque hubiera CPU | Mide ticks CPU por PID y tiempo de creación; excluye zombis y PID reutilizado |
| Un envío UDP fallido simulaba respuesta de WinHandler | Solo una respuesta real notifica al monitor |
| Aviso repetido y espera sin salida, porcentaje 95 constante | Aviso por episodio; tiempo de espera, pantalla real, volver y compartir registro |
| Telemetría quedaba fuera de pantalla horizontal corta | Columna desplazable y acciones visibles |
| WINEDEBUG generado como +err | Usa clase `-all,err+all`; detalle opcional. +err no es err+all, pero no prueba que antes todos los errores se suprimieran |
| Preparación de entorno sin manejo visible de excepción | Error registrado y retorno al launcher; hilo termina tras preparar |

El monitor recoge una vez los últimos 4 KiB de bootstrap_log.txt y cef_log.txt, si existen, después de falta de actividad o tres minutos sin ventana. No recorre juegos ni userdata para exportar su contenido. Compartir requiere pulsar el botón de Android.

## Validación y límites

Pruebas Java: streams stdout/stderr, código real de salida, executable ausente, callback único, dieciséis lanzamientos sin lectores/espera vivos, clase/título tardíos, exclusión de escritorio, CPU, PID reutilizado y UID.

Prueba Android 35: mismos casos de procesos, integración real con ProcessHelper y /proc, terminación por señal y UI a diez minutos en 1280×720/densidad 240. Se mantiene la extracción nativa real con heap 192 MiB. CI también compila ARM64, firma e inspecciona APK.

El emulador x86_64 prueba Android y nuestra integración; no ejecuta el motor Wine/Box64 ARM64 ni Mali. La causa concreta de un cliente Steam que no crea ventana requiere el registro del teléfono. No se promete apertura ni reducción de tiempo sin esa prueba. La limpieza de familias Wine huérfanas tras muerte abrupta del motor merece una prueba específica de ciclo de vida; M24 conserva el mecanismo de parada de la base.

## Fuentes primarias

- [Winlator 11.2 y Steam Legacy](https://github.com/brunodev85/winlator/releases)
- [Código base auditado](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9)
- [Box64: variables y perfiles](https://github.com/ptitSeb/box64/blob/main/docs/USAGE.md)
- [WineHQ: uso de -all,err+all para diagnóstico](https://www.winehq.org/pipermail/wine-bugs/2013-July/361058.html)

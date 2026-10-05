# M26 · Recuperación de procesos Steam

## Problema y comportamiento

El lanzador fijado de Winlator mata únicamente el PID principal de Box64 al detener el entorno. Wine, wineserver o Steam pueden tener descendientes que sobreviven a una salida anormal. Esto puede retener RAM, archivos abiertos o bloqueos entre intentos. M26 no atribuye todas las trabas observadas a esta causa.

Cada lanzamiento recibe `DROIDDECK_STEAM_RUNTIME=<ruta absoluta del rootfs>`. Al iniciar y detener, se examinan una vez los procesos: UID propio, marcador exactamente igual y starttime válido. Se releen UID, marcador y starttime antes de señalizar. Se excluyen la app, PID 0/1, zombies, filas inválidas, otros UID, otros rootfs y procesos sin marcador. El instalador y las descargas Android no llevan ese marcador. No se eliminan archivos de Steam, juegos ni partidas. Los avisos cuentan señales solicitadas, no una medición de memoria liberada.

Un contador de generación impide que la salida tardía del lanzamiento anterior borre el PID de uno nuevo o entregue su callback. La ejecución y la recuperación siguen serializadas por el lock compartido del lanzador. La comprobación de `/proc` no se repite durante el juego.

Se registra una instantánea de RAM total/disponible, límite Java y presión del sistema. La restricción de procesos hijos se informa por separado: conocida activa, conocida desactivada o desconocida. No se modifica Android ni se bloquea Steam por una lectura desconocida. En Android 12 API 31 no se pretende verificar DeviceConfig desde la app.

## Validación y límites

Prueba JVM: selección por UID y marcador exacto, conservación de instaladores sin marcador y otros rootfs, app/zombies, filas ilegibles, datos mayores a 64 KiB y señales rechazadas. Prueba con procesos reales: un hijo marcado termina mientras otro sin marcador continúa. La misma prueba se ejecuta dentro de una APK en Android 35, junto a los controles de interfaz y extracción ya existentes.

El host utiliza un namespace de PID cuyo `/proc` muestra IDs externos. Solo el adaptador de prueba convierte `NSpid` al PID señalizable; la implementación Android usa su `/proc` y `Process.killProcess`.

La recuperación es conservadora: si una ROM oculta `environ`, si Wine borra el marcador o si el proceso pertenece a una APK anterior a M26, se omite. La relectura reduce la carrera de reutilización de PID; no es una operación atómica pidfd. No se mata por nombre ni por “todos los procesos del UID”. Falta medir Steam completo y el ahorro efectivo de RAM en hardware ARM/Mali.

## Investigación relacionada

[Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck/tree/beb96e6f0c891ab5692e73ca7d9d7c970b7e057f) utiliza recuperación de procesos, diagnóstico de restricciones y sesiones observables. M26 contiene implementación propia conservadora; no copia su barrido de casi todos los procesos del UID. Su motor declara Mali no compatible. Los cambios de PRoot no se trasladan al lanzador directo de Box64 usado aquí.

# Investigación · Caché de traducción y ejecución ARM64

Revisión 2026-10-05 UTC. Objetivo: Steam-only, español, Mali-first, Android de 4 GB o más. No confundir espacio en disco, heap Java y RAM nativa.

## Hallazgo inmediato: DynaCache existe en nuestro binario

La base fijada de Winlator 11.2 incluye Box64 0.4.4. Su `GuestProgramLauncherComponent.addBox64EnvVars` establece `BOX64_DYNACACHE=0`. Se examinó el binario ARM64 distribuido en `box64-0.4.4.tzst`: incluye las opciones DynaCache, carga, serialización, limpieza y límites. La [documentación de esa versión](https://github.com/ptitSeb/box64/blob/v0.4.4/docs/USAGE.md#box64_dynacache) describe guardar código generado y reutilizarlo. No supone traducir todo Steam de antemano ni evita la traducción de código nuevo o autogenerado por CEF.

Valores: 0 desactivado; 1 lectura/escritura; 2 solo lectura de caché existente. En esta versión se documentan carpeta, límite en MiB, compresión y tamaño mínimo. El límite upstream es 2048 MiB de disco; no es presupuesto de RAM. Para un experimento móvil debe usarse una carpeta privada y un límite de disco mucho menor, observable y reversible. M26 conserva el valor 0. M27 ofrece activación experimental en Diagnóstico, con carpeta privada compartida de caché y objetivo de disco de 128 MiB; no declara mayor fluidez por encontrar el interruptor.

Experimento siguiente: motor/Wine/paquete/preset fijados, Steam sin caché, primer arranque con caché y segundo arranque caliente. Registrar tiempo hasta ventana útil, memoria Java/nativa, CPU por proceso, archivos de caché y tirones. Confirmar que realmente carga bloques, probar actualización, archivo corrupto, disco lleno y cierre forzado. Limpiar únicamente caché propia; conservar userdata y juegos. No compartir código generado entre teléfonos o versiones sin validar la clave y la invalidación upstream. No elevar el preset agresivo ni quitar barreras de memoria para “compensar” una caché que no carga.

## FEX y Proton ARM64

[FEX 2609](https://fex-emu.com/FEX-2609/) documenta una caché experimental persistente. Es otra implementación de traducción; no se mezcla poniendo variables FEX en Box64. [Proton ARM64](https://github.com/ValveSoftware/Proton/tree/proton_11.0) permite estudiar Wine ARM64EC y ejecución de partes nativas ARM64. SteamOS es un sistema y una integración completa: reutilizar componentes no convierte Android automáticamente en SteamOS ni elimina las limitaciones de Vulkan de Mali.

## Steam ARM64 bajo Termux

[moio9/steam-arm64-termux](https://github.com/moio9/steam-arm64-termux) publica una ruta de Steam y CEF ARM64 bajo glibc, con Wine Hangover Bionic, Box64/FEX y puente Steamworks. Su README informa pruebas en Adreno 740/Turnip y requiere Termux:X11, audio y un driver funcional. No establece compatibilidad Mali-G57. Es candidato para una prueba aislada futura: reducir la traducción del cliente es prometedor, pero el puente, DRM, bibliotecas, autoactualizaciones y GPU deben verificarse. No integrar su runtime completo en main sin esa prueba.

## Qué ya se hace directamente en Android

Interfaz, descarga/reanudación/verificación del paquete, recuperación de instalación, diagnósticos y extracción C/JNI son propios Android. No necesitan Windows para funcionar. Audio, entrada, almacenamiento y gráficos pueden estudiarse por separado sin añadir un escritorio Linux completo. Las partes Windows x86 de Steam y los juegos aún necesitan traducción/compatibilidad. La caché de shaders es distinta a DynaCache: no borrar ninguna de ellas indiscriminadamente ante un error de descarga.

## Evidencia pendiente

Las pruebas x86_64 del host y del emulador Android verifican nuestra lógica, memoria de extracción e interfaz. No ejecutan el Box64 ARM64 ni reproducen Mali. El resultado que decide un cambio predeterminado será una comparación en teléfono ARM64/Mali, no las cifras de un proyecto en Snapdragon.

## Prueba funcional ARM64 realizada · M27

Se ejecutó el binario ARM64 real de Winlator Box64 0.4.4, con el loader/glibc ARM64 de su rootfs, bajo QEMU user-mode 7.2. Un pequeño programa x86_64 produjo `RESULT=17849950052195505252`. El primer arranque generó un archivo de 8192 bytes; el segundo informó la carga de un bloque y produjo el mismo resultado. El modo de solo lectura conservó el contenido de la caché. Una cabecera corrupta se rechazó y regeneró; una carpeta inaccesible y el modo desactivado continuaron sin cargar caché. Cambiar la función traducida, conservando el tamaño del ELF, produjo el resultado actualizado `6311175984321895946`.

`tools/test_box64_dynacache.py` reproduce esos casos y guarda los logs. Solo la prueba reduce `BOX64_DYNACACHE_MIN` a 0 para su fixture pequeño; la APK conserva el umbral normal. CI usa QEMU del runner y publica `box64-cache-proof`. No se usa la duración bajo QEMU como medida de velocidad en el teléfono. El programa de prueba ELF no verifica cuánto del cliente PE/JIT de Steam puede cachearse ni sus condiciones de seccomp Android.

La lectura de `src/tools/dynacache.c` de Box64 v0.4.4 confirmó validación de formato, hashes de dynarec/backend, tamaño de página, extensiones CPU, checksums de cabecera/payload, ruta/tamaño del binario y ajustes de traducción. La prueba de binario modificado comprueba el resultado real, aunque el loader pueda anunciar bloques cargados que después se validan al usarlos.

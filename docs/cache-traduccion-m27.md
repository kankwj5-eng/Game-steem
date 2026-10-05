# M27 · Reutilización experimental de traducciones

En **Diagnóstico** aparece **Reutilizar traducciones en próximos inicios (experimental)**. Se aplica al próximo arranque y está desactivado inicialmente. El primer arranque genera código; los siguientes pueden reutilizar los bloques compatibles que Box64 consiga persistir. No es una pretraducción de todos los juegos ni de todo el JIT de Chromium.

La caché se aloja exclusivamente en `Context.getCacheDir()/steam-translation`. Android puede eliminarla por presión de almacenamiento; se vuelve a generar. Box64 recibe modo 1, compresión rápida y objetivo de disco de 128 MiB (su política de limpieza no es un límite instantáneo estricto). Este valor no es un límite de RAM. Se conserva el umbral normal de serialización, la versión 0.4.4, los perfiles y argumentos Steam de Winlator 11.2.

Una carpeta no disponible o redirigida con un enlace provoca inicio sin caché. Desactivar la opción conserva los archivos, pero Box64 no los carga. No se eliminan userdata, juegos, partidas ni caché de shaders. La carpeta única evita multiplicar presupuestos de disco por versiones; las comprobaciones de compatibilidad e integridad permanecen en Box64.

Pruebas JVM/Android: opción desactivada sin escribir, activación en carpeta privada, límite configurado, conservación al desactivar, fallo de directorio y rechazo de symlinks. Prueba ARM64 QEMU con el binario real: generación, reutilización, solo lectura, corrupción, almacenamiento inutilizable y cambio de binario conservando tamaño. Ver [investigación y límites](investigacion-cache-y-arm64.md).

Pendiente: comparar Steam completo frío/caliente en ARM64/Mali con la misma configuración y registrar RAM, CPU, tiempo hasta ventana útil y tirones. La mejora de fluidez no se deduce de que un bloque haya sido reutilizado. Para comparar, dejar el registro detallado apagado; activarlo solo para confirmar carga de DynaCache, pues añade coste.

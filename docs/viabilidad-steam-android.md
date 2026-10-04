# Steam en Android: decisión técnica y pruebas

Revisión: 4 de octubre de 2026. Se conserva Winlator 11.2, Wine y Box64.

## Qué es posible y qué falta demostrar

Ejecutar software Windows x86 sobre ARM64 es técnicamente viable. Valve documenta juegos Windows en Steam Frame mediante Proton (Wine y DXVK) y FEX sobre SteamOS y Snapdragon 8 Gen 3. Ese resultado no acredita automáticamente Android, Mali-G57 ni teléfonos de 4 GB. El cliente Steam, el inicio de sesión, la GPU y cada juego tienen contratos diferentes. Descargar y extraer Steam no prueba que el cliente pueda conectarse ni que un juego funcione.

| Ruta | Utilidad para este proyecto | Decisión |
|---|---|---|
| Winlator + Wine + Box64 | Ya integrado con contenedor, XServer, audio y controladores Android | Mantener y medir |
| Proton | Es Wine con componentes adicionales, no elimina la traducción de CPU | Evaluar componentes por separado con pruebas de GPU |
| FEX/FEXCore | FEX Linux no tiene Android como objetivo; FEXCore puede integrarse en otro motor Android | Investigación futura aislada, sin sustituir un motor completo sin pruebas |
| Termux / PRoot-Distro | Útil para laboratorio; PRoot intercepta llamadas mediante ptrace, lo que añade coste de E/S | No añadir una segunda capa al arranque de esta APK |
| Cliente Steam Linux/ARM64 y puentes Bionic/glibc | Requiere otra arquitectura de ejecución y validar bibliotecas y GPU | No es una corrección del fallo actual |

Son decisiones de ingeniería para esta base, no un benchmark que demuestre que Winlator vence a todas las alternativas.

## Fallo de memoria reproducido y corrección M23

El archivo fijado de Winlator Addons tiene 215.772.926 bytes, SHA-256 `f5771fed575afb8ef8a133ee28e34a6b4191a366943d0ff7505eab3846b3d19c`, 6.181 registros y 778.291.219 bytes descomprimidos. Un bloque LZMA2 declara diccionario de 256 MiB. SevenZFile lo reserva en el heap Java; con límite Android de 256 MiB la instalación falla antes de terminar. La captura del teléfono coincide con esa reserva; no permite deducir que Steam ya se estuviera ejecutando.

M23 usa el decoder C de 7-Zip, fijado por commit y SHA, mediante JNI. Mantiene SHA del paquete, CRC del decoder, comprobación de tamaño, staging, marcador y recuperación. El presupuesto explícito es de 640 MiB nativos y se rechazan bloques superiores a 512 MiB. El formato sólido obliga a mantener un bloque de aproximadamente 490 MiB; trasladarlo fuera de Java corrige el límite Java, pero no elimina su coste físico.

Prueba local x86_64 con el paquete completo: heap Java limitado a 64 MiB; pico de asignaciones del decoder 552.337.637 bytes (aproximadamente 527 MiB); extracción en unos 10,5 segundos. Este tiempo corresponde al entorno de desarrollo, no al teléfono. La memoria indicada incluye asignaciones controladas del decoder, no el RSS total del proceso. El buffer se libera al finalizar o fallar. No se activa `largeHeap`.

## Validación reproducible

- `tools/test_steam_installation.sh`: instalación ausente, incompleta, marcador incorrecto, recuperación, conservación de carpeta previa y resumen de errores.
- `tools/test_native_steam_archive.sh`: paquete real, progreso monotónico, seis binarios PE, presupuesto de memoria, archivo inexistente y rechazo de symlinks que salen del staging.
- Job `android_archive`: compila el mismo JNI para x86_64, ejecuta el paquete real en Android 35 con heap de 192 MiB y comprueba que el botón Steam sea alcanzable mediante scroll en pantalla horizontal corta. Guarda resultados, logcat y captura.
- Job `build`: compila y firma la APK de producción ARM64, comprueba identidad, certificado y presencia de `libsteamarchive.so`.

El emulador x86_64 con SwiftShader verifica Android/JNI y disposición de la pantalla. No ejecuta Wine/Box64 ARM64 ni acredita Vortek/Gladio sobre Mali. El entorno local no dispone de `/dev/kvm`; la prueba Android se ejecuta en Actions.

## Próxima medición en teléfono

Registrar por separado preparación, verificación SHA, extracción, creación del contenedor, aparición de primera ventana y biblioteca utilizable. Medir arranque frío y segundo arranque, Java heap, RSS/PSS del launcher y procesos Wine/Steam, presión de memoria y temperatura. Mantener los logs detallados desactivados salvo diagnóstico. Una instalación válida no debe descargar ni extraer nuevamente. Los ajustes de CEF, Box64 y GPU deben probarse con inicio de sesión y biblioteca reales antes de prometer menor latencia.

## Fuentes primarias consultadas

- [Valve: compatibilidad Steam Frame](https://partner.steamgames.com/doc/steamhardware/steamframe/compatibility)
- [Valve: Proton](https://github.com/ValveSoftware/Proton)
- [Box64](https://github.com/ptitSeb/box64)
- [FEX: FAQ y límites Android/FEXCore](https://wiki.fex-emu.com/index.php/FAQ)
- [Termux: PRoot-Distro, limitaciones de rendimiento](https://github.com/termux/proot-distro#limitations)
- [7-Zip C decoder fijado](https://github.com/ip7z/7zip/tree/0766b733fe3e06dd2a7f9a3cfbf2108ac73abd17/C)
- [Paquete Steam Legacy fijado](https://github.com/brunodev85/winlator-addons/releases/download/v1.0.0/steam-legacy.7z)

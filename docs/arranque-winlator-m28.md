# M28: volver al escritorio de Winlator para iniciar Steam

La captura del teléfono demuestra que Winlator 11.2 alcanza el inicio de sesión. Las pruebas de M27 sólo demostraban compilación, extracción y componentes aislados; no demostraban ese arranque completo en ARM64/Mali.

## Diferencias corregidas

- El launcher enviaba `exec_path`, que selecciona `explorer /desktop=nogui` y oculta el escritorio. M28 arranca el escritorio `shell` con `C:\windows\winhandler.exe /dir C:\windows "wfm.exe"`, igual que un contenedor normal de la fuente fijada de Winlator 11.2. Envía después la ruta DOS de Steam con `WinHandler.exec(filename, null)`. La cola original espera el mensaje `INIT` del helper de Windows antes de ejecutar la petición; no añade tiempos de espera ni reintentos de lanzamiento.
- M26 etiquetaba y eliminaba procesos hijos de Wine al iniciar y detener el componente, incluso en operaciones internas como `wineboot -u`. M28 retira esa integración del ciclo de vida y vuelve a detener sólo el PID principal como Winlator. Conserva el guard de generación para ignorar callbacks de una sesión previamente detenida. El helper de recuperación queda disponible para pruebas, pero no se invoca desde producción.
- `refreshAfterResume` podía reemplazar un fallo del motor por el estado de instalación verificada. M28 conserva el error hasta un nuevo intento de inicio. Una instalación válida sigue sin volver a descomprimirse: esto es normal y evita sobrescribir archivos del usuario.

Se conservan Wine, Box64, el perfil predeterminado de rendimiento de Winlator, sus argumentos Steam, la instalación nativa que evita el diccionario 7z en el heap Java y los controles de integridad/recuperación del paquete. La caché de traducción sigue siendo opcional y está desactivada inicialmente.

## Comprobaciones y límites

`test_winlator_desktop_start.py` compara las líneas de construcción del comando con el Winlator fijado y compila/ejecuta los cuerpos reales de los métodos de cola y EXEC/INIT. Verifica que no se entrega EXEC antes de INIT, que la ruta con espacios llega intacta y que otro INIT no duplica la petición. CI también exige Gradle, firma estable, inspección APK, extracción real del archivo en Android y los validadores anteriores.

No se ha reproducido el fallo del teléfono en un dispositivo ARM64/Mali. Este cambio restaura una ruta de arranque compatible con la captura y corrige el ocultamiento de errores; todavía necesita confirmar el inicio de sesión en ese teléfono. No constituye una medición de rendimiento ni una garantía de que se haya identificado toda la causa del parpadeo.

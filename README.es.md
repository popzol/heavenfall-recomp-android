# Heavenfall para Android

**Heavenfall para Android es una versión modificada de [4u-native](https://github.com/khorne-hub/4u-native) (MH4U para
Android y PC) de khorne-hub. No es la original, y no la hizo ni la respalda khorne-hub.**

Juega tu propia copia de Monster Hunter 4 Ultimate en Android con el mod Heavenfall, de forma nativa y con controles
táctiles pensados para teléfonos. No es un emulador: el código del juego se recompila a código nativo para tu teléfono, y
corre sobre un runtime propio que hace el trabajo de la consola, con los gráficos dibujados con Vulkan.

[Read this guide in English](README.md)

**Este proyecto no incluye código del juego, archivos del juego, ROMs ni keys. Necesitas tu propia copia del juego.** La
app y sus versiones publicadas no contienen datos del juego. El código del juego se arma en tu teléfono, con tus propios
volcados del juego, y se queda ahí: no lo compartas nunca.

## La app

Heavenfall es una sola app que prepara el mod, lo mantiene al día y arranca el juego. Nada del juego va en la app ni en
sus versiones publicadas: todo se hace en tu teléfono a partir de tu propia copia del juego.

**Qué pide la primera vez.** Una guía te acompaña, y puedes dejarla y volver:

1. Dejar que la app lea archivos, y elegir la carpeta de tu teléfono donde están (o estarán) los archivos de tu juego.
2. Tu propia copia sin cifrar del juego americano, Monster Hunter 4 Ultimate (un archivo `.3ds`, `.cci` o `.cxi`).
3. Tu propia copia sin cifrar de la actualización 1.3.0 del juego (un archivo `.cia`).
4. Opcional: tu propia copia sin cifrar de Monster Hunter XX, para sumar sus aspectos de equipo y materiales raros. Puedes
   omitirla.

Copia los archivos a la carpeta con un cable USB o un administrador de archivos, y toca Comprobar de nuevo. Las copias
cifradas no sirven: vuelca tu propio juego sin cifrar desde tu 3DS (por ejemplo con GodMode9). La app revisa cada archivo
y dice qué falla.

**El botón de la primera pantalla** dice lo que la app va a hacer, y lo hace todo bajo una sola barra:

- **Instalar:** descarga los archivos del mod, hace el mod con tus volcados y arma el código del juego.
- **Actualizar a una versión:** lo mismo para una app nueva, un mod nuevo o ambos. La primera vez la app pide
  permiso para instalarse a sí misma y te enseña cómo darlo. Después se reemplaza y sigue.
- **Construir:** arma solo el código del juego otra vez (falta, o se hizo para otro mod). En Más también están Construir de nuevo
  y Reinstalar el mod.
- **Continuar:** sigue donde se detuvo un trabajo. Lo que ya se hizo no se repite.
- **Jugar:** arranca el juego.

**Cuánto tarda.** Descargar el mod tarda minutos. Armar el código del juego es lo largo: puede llevar muchos minutos y, en
un teléfono modesto, horas. Deja el teléfono enchufado y, si quieres, la app en segundo plano: el trabajo sigue, con una
notificación (Android 13 y posteriores preguntan antes si se muestra; el trabajo corre igual sin ella), y la primera
pantalla muestra el tiempo que falta. Si el teléfono se reinicia o cancelas, Continuar sigue desde donde quedó. Armar
necesita espacio libre y memoria: la app avisa antes de empezar si el teléfono tiene poco.

El código del juego se queda en tu teléfono: no lo compartas nunca.

### Nombre, identificador y versiones publicadas

- **Nombre e identificador:** "Heavenfall", identificador de aplicación `io.github.popzol.heavenfall`. Se instala junto a
  4u-native como una app aparte: no comparten partida, ajustes ni actualizaciones.
- **Versiones de prueba:** la de depuración se llama "Heavenfall test", con identificador
  `io.github.popzol.heavenfall.test`, así que una versión de prueba nunca reemplaza tu instalación.
- **Versiones publicadas:** `Heavenfall-android.apk` y su suma `Heavenfall-android.apk.sha256`, en las
  [Releases](https://github.com/popzol/heavenfall-recomp-android/releases) de este repositorio. Todas están firmadas con la
  misma clave. Para revisar el archivo en una PC: `sha256sum -c Heavenfall-android.apk.sha256`.
- **Versión:** `heavenfall/VERSION` es la versión de la app (`versionName`; `versionCode` crece con ella). El archivo
  `VERSION` es el de 4u-native, nombra el motor, y se queda como está.
- **Todo lo demás de la app, cómo preparar el juego y sus ajustes,** es como en la guía propia de 4u-native, que lo explica
  para la original: [README](https://github.com/khorne-hub/4u-native/blob/main/README.es.md).

## Qué cambia respecto a 4u-native

- El nombre, el identificador de aplicación, el ícono y las versiones publicadas (arriba), y la clave con que se firman.
- Los créditos de la app dicen que es una versión modificada, y dónde está la original.
- Se quitan la revisión que 4u-native hacía de sus propias versiones y su aviso sobre el certificado de firma del APK.
- La autoridad con que se comparte el registro sigue al identificador de aplicación.

Los cambios están en el historial de este repositorio, que incluye también el de la original.

## Compilar la app

En Windows (Git Bash), Linux o macOS, con el SDK de Android (plataforma 35) y el NDK 27.0.12077973, CMake 3.25 o superior,
Ninja, Python 3.10 o superior y un JDK 17:

```
sh scripts/build_sdl3.sh    # SDL 3.4.18 desde su release, una vez
sh scripts/build_apk.sh     # libmain.so, las librerías de hooks, el armado en el teléfono y después Gradle
```

`scripts/android_env.sh` dice dónde se busca cada herramienta. Sin `android/keystore.properties` y `android/mh4u.keystore`
sale el APK de depuración, `android/app/build/outputs/apk/debug/app-debug.apk`. Las pruebas unitarias:
`cd android && sh ./gradlew testDebugUnitTest`.

Una versión publicada la hace GitHub Actions (`.github/workflows/build.yml`): un push o un pull request arma el APK de
depuración y corre las pruebas; una etiqueta `v<versión>` (la de `heavenfall/VERSION`) arma el APK firmado con los secretos
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`, y crea la versión con `Heavenfall-android.apk`,
`Heavenfall-android.apk.sha256` y `Heavenfall-android-release.json` (los archivos de la versión con la forma de la
respuesta de GitHub, que la app lee cuando no alcanza la API de GitHub). Sin los cuatro secretos la etiqueta falla: nunca firma con la clave de depuración.

`app_native/` es la app (SDL3 y Dear ImGui), `android/` su parte de Android, `heavenfall/` lo que es propio de esta versión
(su versión y su ícono: `python heavenfall/tools/make_icons.py` hace los íconos) y `mh4n/` el runtime y el recompilador
(mira [mh4n/README.md](mh4n/README.md)).

## Legal

Este proyecto no está afiliado, respaldado ni patrocinado por la Gran C ni la Gran N. Monster Hunter es una marca de la Gran
C. 3DS es una marca de la Gran N.

Este repositorio y sus releases no contienen código del juego, datos del juego, ROMs ni keys. Úsalo solo con una copia del
juego que te pertenezca. Los pedidos de archivos del juego, keys o archivos `libmh4u_code_native.so` ya hechos se cerrarán.

Titulares de derechos: abran un issue para ponerse en contacto.

## Créditos

- El recompilador del juego y el runtime nativo vienen de [MH4UltimateDC](https://github.com/CompaLarios18/MH4UltimateDC),
  de CompaLarios18 y sus colaboradores.
- Partes del runtime derivan de [Azahar](https://github.com/azahar-emu/azahar) y Citra.
- Del mismo autor: [MHGU para Android y PC](https://github.com/khorne-hub/gu-native), Monster Hunter Generations
  Ultimate recompilado para Android y Windows de la misma forma. Partes de la versión de PC (sus datos de la PC, su lector
  de zip y Discord Rich Presence) siguen a las del MHGU para PC.
- El mod de una sola pantalla es de [jM5557](https://github.com/jM5557/mh4u-single-screen-mod), y no es parte de este
  proyecto.
- Aportaron al desarrollo:
  - tabarong
  - Klark231
  - Alfonso Matador
  - popzol
  - BlitzWOLF
  - jM5557
- El modo de pantalla única pone los menús donde los pone el modo de una pantalla de
  [Heavenfall](https://github.com/popzol/4u-heavenfall-mod-beta), de popzol y BlitzWOLF (GPL-3.0), sin los cambios que
  Heavenfall le hace al juego.
- El creador de la app, que crea el código del juego en el teléfono, la primera vez descarga Python
  ([python.org](https://www.python.org), licencia PSF) y [Zig](https://ziglang.org) (MIT; con LLVM, Apache 2.0 con
  excepciones de LLVM); sus licencias también están en **Licencias** de la app.
- Las librerías que usa este proyecto, con sus licencias, están en [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES). Sus licencias
  también están en la app: engranaje, **Créditos y licencias**, **Licencias**.

## Licencia

MH4U para Android y PC es software libre bajo la [Licencia Pública General GNU, versión 3](LICENSE) o (a tu elección) cualquier
versión posterior, como el runtime de MH4UltimateDC sobre el que se construye. Las partes de terceros conservan sus propias
licencias.

El código de khorne-hub tiene [términos adicionales](ADDITIONAL-TERMS), como lo permite la sección 7 de la GPL. Quien
comparta la app, modificada o no, tiene que:

- conservar los créditos del autor: los que la app muestra al abrirse, su página **Créditos y licencias**, y las secciones
  **Créditos** y **Licencia** de este archivo;
- indicar que una versión modificada está modificada, y no presentarla como la oficial;
- dar, junto con la app, su licencia y su código fuente completo, como lo pide la GPL.

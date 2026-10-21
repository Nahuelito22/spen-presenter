# App Android — S Pen Presenter (Fase 2)

Captura los eventos del S Pen a través de una `MediaSession` y, por ahora,
solo los registra (lista en pantalla + vibración). El envío a la PC llega en
la Fase 3.

## Toolchain instalado en esta PC

| Pieza | Versión | Dónde |
|---|---|---|
| Android Studio | última (winget `Google.AndroidStudio`) | `C:\Program Files\Android\Android Studio` |
| JDK de build | Temurin **21** | `C:\Program Files\Eclipse Adoptium\jdk-21...` |
| Android SDK | platform **36**, build-tools 36.0.0, platform-tools 37.0.1 | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle | 8.13 (vía wrapper) | `./gradlew` |

> ⚠️ **No usar el JDK que trae Android Studio.** Su JBR es un JDK 25 y Gradle
> 8.13 no lo soporta (falla con un escueto `25.0.3`). Por eso instalamos
> Temurin 21, que quedó como `JAVA_HOME` del sistema.
>
> Si abrís el proyecto en Android Studio, cambiá
> *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*
> a **Temurin 21**, o la compilación desde la GUI va a fallar igual.

## Compilar e instalar

```powershell
cd android
.\gradlew.bat assembleDebug                 # genera app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug                  # compila e instala en el telefono conectado
```

Con el teléfono por USB y *Depuración USB* activada:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices                              # debe listar el S22
& $adb logcat -s PresenterService SilentPlayer   # ver los eventos en vivo
```

## Compilar una versión firmada

El APK de depuración sirve para probar, pero va firmado con la clave genérica
de Android y queda marcado como *debuggable*. Para publicar uno de verdad:

1. Generá el keystore (**una sola vez**; si lo perdés no vas a poder firmar
   actualizaciones de la misma app, y los usuarios tendrán que desinstalar y
   reinstalar):

   ```powershell
   cd android
   keytool -genkeypair -v -keystore spen-presenter.jks -alias spen-presenter -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Copiá `keystore.properties.example` a `keystore.properties` y completá las
   contraseñas que acabás de elegir.

3. Compilá:

   ```powershell
   .\gradlew.bat assembleRelease
   ```

   Queda en `app\build\outputs\apk\release\app-release.apk`.

> `keystore.properties` y los `.jks` están en `.gitignore`. Si el archivo no
> existe el proyecto igual compila: el release sale sin firmar, así cualquiera
> puede clonar y construir sin necesitar tus claves.

## Cómo está armado

| Archivo | Qué hace |
|---|---|
| `MainActivity.kt` | UI Compose: Iniciar/Detener, lista de eventos, pide `POST_NOTIFICATIONS` |
| `PresenterService.kt` | Foreground service + `MediaSessionCompat` + notificación + vibración |
| `SilentPlayer.kt` | Audio silencioso en loop y foco de audio (es lo que nos da prioridad) |
| `Mapping.kt` | Evento del S Pen → comando del protocolo |
| `EventLog.kt` | Puente en memoria entre el servicio y la UI |
| `res/raw/silence.wav` | 2 s de silencio, generado localmente (8 kHz mono) |

## Por qué hace falta el audio silencioso

Android le manda los botones de media a la sesión que **está reproduciendo**.
Sin audio sonando, nuestra `MediaSession` no gana prioridad y el S Pen le
sigue hablando a Spotify. Por eso también pedimos el foco de audio — efecto
esperado: al iniciar, se pausa la música que estuviera sonando.

## Gestos de volumen (experimental)

La sesión se declara como *remote playback* con un `VolumeProvider`, así los
gestos ↑/↓ del S Pen llegan como `onAdjustVolume` en vez de mover el volumen
del teléfono. Si Air Actions llama al `AudioManager` directo, estos eventos no
van a aparecer nunca: en ese caso se borra el `setPlaybackToRemote` de
`PresenterService` y nos quedamos con los 4 gestos restantes.

Mientras el servicio corre, **las teclas físicas de volumen no cambian el
volumen del teléfono**: se las queda nuestra sesión. Es el precio de tener
dos gestos más.

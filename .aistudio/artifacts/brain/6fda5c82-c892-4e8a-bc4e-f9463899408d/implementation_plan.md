# Radio Mía — Prototipo de Radio FM Interactiva con Intervención de Locutor

**Radio Mía** es una aplicación nativa de Android desarrollada en Kotlin con Jetpack Compose y Jetpack Media3 que reproduce canciones almacenadas localmente en el teléfono (y pistas instrumentales de respaldo libres de derechos) mientras permite superponer la intervención de un locutor (`demo_locutor.wav`) con atenuación gradual configurable (*audio ducking*), manteniendo la música sonando en todo momento tanto en primer como en segundo plano.

---

## Revisión del Usuario y Decisiones Confirmadas

> [!IMPORTANT]
> Este plan incorpora todas las decisiones iniciales y los 10 ajustes de arquitectura y experiencia solicitados para la validación del prototipo.

1. **Identidad Sin Frecuencias Reales ni Inventadas**: La cabecera y los indicadores de transmisión utilizarán exclusivamente la leyenda **“RADIO MÍA — AL AIRE”**, sin incluir números de dial o frecuencias en MHz.
2. **Archivos de Audio Verificados y 100% Libres de Derechos**: `demo_locutor.wav` y las pistas musicales de respaldo se generarán e incluirán como archivos WAV reales, válidos y verificables dentro del proyecto (además de contar con un generador WAV PCM de respaldo en tiempo de ejecución si se requiere). Solo se utilizará audio original de demostración libre de derechos, sin canciones comerciales.
3. **Alcance Estricto del Prototipo (Sin APIs Externas)**: No se integrarán Groq, Llama, Spotify, YouTube Music, mensajes de oyentes ni claves de API externas. El foco exclusivo es validar la lectura local con `MediaStore`, las pistas de respaldo, el segundo reproductor de voz y el sistema de *ducking*.
4. **Reproducción Real en Segundo Plano (`MediaSessionService`)**: Tanto el reproductor de música como el reproductor de voz del locutor vivirán dentro de un `MediaSessionService` configurado como servicio de primer plano (`mediaPlayback`), garantizando que la canción y la locución continúen sin cortes al bloquear la pantalla o cambiar a otra aplicación.
5. **Música Continua Durante la Intervención**: La canción nunca se pausa cuando interviene el locutor; continúa reproduciéndose a volumen reducido durante toda la locución.
6. **Respeto del Volumen Maestro Modificado Durante la Intervención**: Si el usuario mueve el deslizador de volumen maestro mientras una intervención está en curso, el sistema actualiza el volumen objetivo de retorno y lo respeta al finalizar el *fade-up*.
7. **Cancelación Limpia al Re-presionar “Probar locutor”**: Si se presiona nuevamente **“Probar locutor”** (o **“Intervención del DJ”**) durante una intervención activa, se cancela de inmediato la corrutina activa, se detiene el reproductor de voz (`demo_locutor.wav`) y se recupera gradualmente el volumen desde el punto actual hacia el volumen maestro, sin dejar corrutinas huérfanas ni reproductores activos.
8. **Panel de Depuración Visible pero Plegable**: El panel de depuración estará siempre accesible en la pantalla con una cabecera visible de estado rápido y podrá plegarse/desplegarse con un toque para no ocupar toda la pantalla.

---

## 1. Visión General y Concepto Principal

- **Qué hace**: Escanea el almacenamiento del dispositivo mediante `MediaStore` para listar canciones locales, las combina con pistas instrumentales originales libres de derechos para pruebas inmediatas en el emulador o en un teléfono físico, y ejecuta la reproducción mediante un servicio en segundo plano de Jetpack Media3. Al activar la intervención del locutor, atenúa suavemente el volumen de la música, reproduce `demo_locutor.wav` en paralelo y restaura gradualmente el volumen al terminar.
- **Público y Contexto de Uso**: Validación técnica y de experiencia de usuario del núcleo de mezcla de audio radial en Android (en el emulador de Google AI Studio y en dispositivos físicos).
- **Valor Principal**: Control determinístico y transparente del *ducking* musical con dos instancias sincronizadas de `ExoPlayer`, transiciones suaves sin cortes y telemetría en vivo plegable.

---

## 2. Experiencia de Usuario y Diseño Visual

### Flujos Principales de Usuario

1. **Permisos Modernos de Audio y Carga de Biblioteca**:
   - Solicitud del permiso correspondiente según la versión de Android (`READ_MEDIA_AUDIO` en Android 13 / API 33+ o `READ_EXTERNAL_STORAGE` en Android 12 e inferiores).
   - Incluso antes de conceder el permiso o si el emulador no tiene archivos MP3 propios, el usuario dispone de las pistas instrumentales de respaldo libres de derechos para probar de inmediato todas las funciones.
2. **Lista de Canciones (“Discoteca Local y Demo”)**:
   - Cada elemento de la lista muestra:
     - **Portada** (carátula del álbum desde `MediaStore` o arte vectorial original para pistas sin portada / de respaldo)
     - **Título**
     - **Artista**
     - **Álbum**
     - **Duración** (`mm:ss`)
   - Al tocar cualquier canción, comienza a reproducirse de inmediato con Media3 / ExoPlayer y se actualiza el reproductor activo.
3. **Pantalla de Reproducción (“RADIO MÍA — AL AIRE”)**:
   - Cabecera superior con el cartel luminoso **“RADIO MÍA — AL AIRE”**, que resalta cuando el locutor está interviniendo.
   - Visualización de **Portada**, **Título** y **Artista** de la canción actual.
   - **Barra de progreso** interactiva con tiempo transcurrido y duración total.
   - Controles de transporte: **Canción anterior**, **Reproducir y pausar**, y **Canción siguiente**.
   - **Control de volumen maestro**: Deslizador que ajusta el volumen principal de la música. Si el usuario lo mueve mientras el locutor habla, el nuevo valor queda registrado como el nivel objetivo al que volverá la canción en el *fade-up*.
   - **Cabina del Locutor**:
     - Botón **“Intervención del DJ”** y botón de prueba **“Probar locutor”** (con comportamiento de activación y cancelación limpia si ya hay una intervención activa).
     - Deslizadores para configurar el **nivel de volumen durante la intervención (%)** y el **tiempo de transición gradual (*fade*)**.
4. **Panel de Depuración Plegable (“Monitor Técnico”)**:
   - Ubicado en la pantalla de reproducción con un encabezado siempre visible y un botón para expandir o plegar el detalle completo, mostrando en tiempo real:
     - **Canción actual**
     - **Volumen original** (y volumen instantáneo durante las rampas)
     - **Volumen durante la intervención** (nivel configurado de *ducking*)
     - **Estado de reproducción** (estado de la música + fase de la intervención del locutor)
     - **Duración del audio del locutor** (progreso actual y duración total de `demo_locutor.wav`)

### Identidad Visual y Tema (“Estudio FM Nocturno”)

- **Dirección Estética**: Cabina de radio nocturna moderna con identidad 100% original en español argentino, sin copiar marcas ni diales existentes.
- **Paleta de Colores (Material 3)**:
  - `--background` / `Surface`: Carbón profundo de cabina (`#0E1015`) y superficies elevadas (`#171B24`, `#1F2532`).
  - `--primary`: Ámbar cálido (`#FFB347`) para controles principales, barras de progreso y volumen maestro.
  - `--secondary` / `--accent`: Coral luminoso (`#FF5252`) para el cartel **“RADIO MÍA — AL AIRE”** y la intervención activa del locutor.
  - `--tertiary`: Celeste técnico (`#4DD0E1`) para los indicadores del panel de depuración plegable.
- **Tipografía Local**:
  - **Space Grotesk** para encabezados y cartel “RADIO MÍA — AL AIRE”.
  - **Plus Jakarta Sans** para textos de interfaz, títulos y artistas.
  - **JetBrains Mono** para tiempos, porcentajes de volumen y datos del panel de depuración.

---

## 3. Decisiones de Producto y Comportamiento Técnico

- **Decisión 1: Ambos Reproductores Alojados en `RadioPlaybackService` (`MediaSessionService`)**
  - *Enfoque elegido*: Tanto el `ExoPlayer` principal (música) como el `ExoPlayer` secundario (`demo_locutor.wav`) y el coordinador de *ducking* residen dentro del servicio en primer plano `RadioPlaybackService`.
  - *Por qué*: Si la lógica del locutor o las corrutinas de volumen vivieran solo en la `Activity` o en el `ViewModel` de UI, podrían suspenderse al bloquear el teléfono o pasar a segundo plano. Al alojarlos en el servicio con `foregroundServiceType="mediaPlayback"`, tanto la canción como la intervención del locutor y las rampas de volumen continúan ejecutándose sin interrupciones con la pantalla apagada o en otra app.
- **Decisión 2: Máquina de Estados Determinística para Ducking, Volumen Maestro y Cancelación**
  - *Enfoque elegido*: Un único `Job` de corrutina (`interventionJob`) gestiona el ciclo `FADING_DOWN -> SPEAKING -> FADING_UP -> IDLE`.
  - *Comportamiento ante cambios de volumen maestro*: La variable `userMasterVolume` almacena siempre el nivel deseado por el usuario. Si el usuario mueve el control de volumen durante la intervención, `userMasterVolume` se actualiza al instante y el paso `FADING_UP` interpola suavemente hasta ese nuevo valor.
  - *Comportamiento al re-presionar “Probar locutor”*: Si `interventionPhase != IDLE`, al presionar nuevamente el botón se cancela `interventionJob`, se detiene y reinicia `voicePlayer` (`stop()`, `seekTo(0)`), y se lanza una única corrutina de restauración desde el volumen actual instantáneo (`currentMusicVolume`) hasta `userMasterVolume`, dejando el estado en `IDLE` sin fugas de recursos.
- **Decisión 3: Verificación de Recursos `demo_locutor.wav` y Pistas Libres de Derechos**
  - *Enfoque elegido*: Incluir `app/src/main/res/raw/demo_locutor.wav` y pistas instrumentales originales libres de derechos en `app/src/main/res/raw/`, verificadas antes de compilar.

---

## 4. Arquitectura Técnica y Estrategia de Datos

### Diagrama de Arquitectura y Ciclo de Vida en Segundo Plano

```
┌───────────────────────────────────────────────────────────────────────────┐
│                        UI Layer (Jetpack Compose)                         │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │               Cabecera: "RADIO MÍA — AL AIRE"                       │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│  ┌─────────────────────────┐   ┌───────────────────────────────────────┐  │
│  │  Lista de Canciones     │   │  Pantalla de Reproducción             │  │
│  │  - MediaStore + Demos   │   │  - Portada, Título, Artista, Progreso │  │
│  │  - Título, Artista,     │   │  - Anterior, Play/Pausa, Siguiente    │  │
│  │    Álbum, Duración,     │   │  - Volumen Maestro (actualizable)     │  │
│  │    Portada              │   │  - "Intervención del DJ" /            │  │
│  │                         │   │    "Probar locutor" (Activar/Cancelar)│  │
│  └────────────┬────────────┘   └───────────────────┬───────────────────┘  │
│               └─────────────────┬──────────────────┘                      │
│                                 ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │         Panel de Depuración Plegable (Expandir / Contraer)          │  │
│  │  Canción | Vol. Original | Vol. Intervención | Estado | Duración DJ │  │
│  └──────────────────────────────┬──────────────────────────────────────┘  │
└─────────────────────────────────┼─────────────────────────────────────────┘
                                  │ StateFlow<RadioUiState>
                                  ▼
┌───────────────────────────────────────────────────────────────────────────┐
│               RadioViewModel & LocalAudioRepository                       │
│  - Escaneo MediaStore.Audio.Media + Pistas Libres de Derechos en res/raw  │
│  - Enlace seguro con el motor del servicio en segundo plano               │
└─────────────────────────────────┬─────────────────────────────────────────┘
                                  │
                                  ▼
┌───────────────────────────────────────────────────────────────────────────┐
│        RadioPlaybackService (Media3 MediaSessionService - Foreground)     │
│                                                                           │
│  ┌──────────────────────────────┐     ┌────────────────────────────────┐  │
│  │   Music ExoPlayer (Canal 1)  │     │    Voice ExoPlayer (Canal 2)   │  │
│  │   Nunca se pausa al hablar   │     │    Carga res/raw/demo_locutor  │  │
│  │   el locutor; solo atenúa    │     │    Notifica duración y fin     │  │
│  └──────────────┬───────────────┘     └───────────────┬────────────────┘  │
│                 └──────────────────┬──────────────────┘                   │
│                                    ▼                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │             DuckingCoordinator (CoroutineScope del Servicio)        │  │
│  │  - Interpolación gradual FADING_DOWN -> SPEAKING -> FADING_UP       │  │
│  │  - Respeta nuevo userMasterVolume si cambia durante la locución     │  │
│  │  - Cancelación limpia + stop voz + fade-up si se vuelve a presionar │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

### Flujo Detallado de “Probar locutor” / “Intervención del DJ”

1. **Activación Normal (`IDLE` → `FADING_DOWN` → `SPEAKING` → `FADING_UP` → `IDLE`)**:
   - La canción actual continúa reproduciéndose (si estaba en pausa, continúa desde su posición sin pausarse durante la locución).
   - Se guarda el volumen maestro actual como referencia en el panel de depuración (`originalVolume`) y se atenúa gradualmente el volumen del `Music ExoPlayer` hasta el nivel configurable (`duckingTargetVolume`).
   - Se inicia `demo_locutor.wav` en el `Voice ExoPlayer` (sin solicitar `AudioFocus` exclusivo para no pausar el `Music ExoPlayer`).
   - Al terminar `demo_locutor.wav`, el volumen de la canción sube gradualmente hasta el volumen maestro vigente (`userMasterVolume`, respetando cualquier ajuste hecho por el usuario durante la intervención).
2. **Cancelación por Segunda Pulsación (Mientras la Intervención está Activa)**:
   - Si el usuario presiona **“Probar locutor”** o **“Intervención del DJ”** mientras el locutor está activo o atenuando:
     - Se cancela la corrutina de intervención actual.
     - Se detiene de inmediato el `Voice ExoPlayer` y se posiciona en `0 ms`.
     - Se ejecuta una transición suave desde el volumen actual instantáneo hasta el volumen maestro vigente (`userMasterVolume`), regresando limpiamente al estado `IDLE`.

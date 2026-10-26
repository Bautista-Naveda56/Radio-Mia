# Radio Mía — Cabina FM Personal con Música Local y Locutor Groq

**Radio Mía** es una aplicación Android desarrollada en **Kotlin** y **Jetpack Compose** que transforma la música descargada en el teléfono (`MediaStore`) en una experiencia de radio FM argentina moderna, nocturna y personalizada, combinando reproducción en segundo plano (`Media3 / ExoPlayer` + `MediaSessionService`), atenuación gradual de volumen (*ducking*) con dos reproductores simultáneos y un **backend seguro en Node.js + TypeScript** integrado con **Groq**.

> **Identidad visual:** Cartel luminoso **`RADIO MÍA — AL AIRE`** (sin frecuencias reales ni inventadas), estética nocturna en tonos carbón, ámbar y coral.

---

## 1. Arquitectura de Seguridad y Flujo de Intervención

```text
Aplicación Android (Kotlin + Compose + Media3)
    |
    |  POST /api/intervention
    |  (envía ÚNICAMENTE metadatos: título, artista, álbum, género, estilo, tipo)
    v
Backend Seguro de Radio Mía (Node.js + TypeScript en backend/)
    |
    |  lee process.env.GROQ_API_KEY desde backend/.env (jamás expuesta al cliente)
    v
API de Groq (https://api.groq.com/openai/v1)
    |
    +--> 1) Generación de guion breve en español rioplatense (JSON estructurado)
    +--> 2) Síntesis de voz en español (/audio/speech) sin sustituir por voz en inglés
    v
Backend devuelve guion + audio (o indicador de respaldo) + clave de caché
    v
Android atenúa gradualmente la música (fade-down), superpone al locutor en el
segundo ExoPlayer sin pausar jamás la canción y recupera el volumen (fade-up).
```

### Regla crítica de seguridad (`GROQ_API_KEY`)
- `GROQ_API_KEY` reside **exclusivamente** en `backend/.env` y se lee mediante `process.env.GROQ_API_KEY`.
- **Nunca** se incluye en Kotlin, Jetpack Compose, `BuildConfig`, recursos Android ni en el APK.
- `backend/.gitignore` y `.gitignore` ignoran `.env`.
- `backend/.env.example` contiene únicamente `GROQ_API_KEY=pegá_tu_clave_aquí`.

---

## 2. Cómo Configurar `GROQ_API_KEY` sin Exponerla

1. Entrá a la carpeta `backend/`.
2. Copiá el archivo de ejemplo si aún no existe `backend/.env`:
   ```bash
   cp backend/.env.example backend/.env
   ```
3. Editá `backend/.env` localmente y colocá tu clave de Groq:
   ```env
   GROQ_API_KEY=tu_clave_real_de_groq
   RADIO_MIA_BACKEND_PORT=3000
   ```
4. No subas `backend/.env` a Git ni lo copies dentro de `app/`.

---

## 3. Cómo Compilar e Iniciar el Backend

Desde la raíz del proyecto:

```bash
# 1. Instalar dependencias del backend
npm install --prefix backend

# 2. Compilar TypeScript y ejecutar pruebas unitarias del backend
npm run test --prefix backend

# 3. Iniciar el servidor en http://0.0.0.0:3000
npm run start --prefix backend
```

### Endpoints disponibles en el backend
- `GET /api/status` (o `/health`): Devuelve estado del servicio, modelo activo, estado de caché y si `GROQ_API_KEY` está presente (`true`/`false`), sin mostrar jamás la clave.
- `GET /api/models`: Consulta en vivo los modelos habilitados en la cuenta de Groq.
- `POST /api/intervention`: Recibe los metadatos de la canción, valida longitud y formato, consulta la caché, genera el guion en español rioplatense e intenta sintetizar la voz en español.
- `POST /api/tts`: Endpoint directo para síntesis Text-to-Speech en español.
- `POST /api/stt`: Endpoint preparado para una etapa futura de transcripción de mensajes de voz de oyentes (`whisper-large-v3-turbo`).

---

## 4. Cómo Configurar la URL del Backend en Android

La aplicación Android incluye resolución automática y configuración en vivo desde la interfaz:

1. Abrí **Radio Mía** en Android.
2. Tocá la barra superior **"PANEL DE DEPURACIÓN — Ver datos"** para desplegar el panel técnico.
3. En el campo **"URL Backend Radio Mía"**:
   - **En Emulador Android:** usá `http://10.0.2.2:3000` (configurado por defecto; también prueba automáticamente `http://127.0.0.1:3000`).
   - **En Teléfono Físico (misma red Wi-Fi):** ingresá la IP local de tu computadora en la red LAN, por ejemplo `http://192.168.1.45:3000`, y tocá el botón de sincronizar.

---

## 5. Instrucciones para Probar en Emulador y en Teléfono Físico

### En el Emulador
1. Asegurate de que el backend esté corriendo en el puerto `3000` (`npm run start --prefix backend`).
2. Ejecutá la app Android. Aunque el emulador no tenga archivos MP3 propios, Radio Mía carga automáticamente **3 pistas instrumentales libres de derechos** (`Medianoche en Corrientes`, `Pulso de Cabina FM`, `Luces de la Costanera`).
3. Tocá **Reproducir** y luego presioná **"Intervención del DJ"** o **"Probar locutor"**:
   - Mientras se consulta el guion a Groq (`Generando intervención`), la canción **sigue sonando al 100% sin pausarse**.
   - Luego el volumen de la música baja gradualmente (`Bajando volumen`), habla el locutor (`Locutor al aire`) y al terminar la voz la música vuelve suavemente al volumen maestro (`Recuperando volumen`).
4. Si volvés a tocar **"Probar locutor"** o **"Cancelar DJ"** en medio de una intervención, se cancela de inmediato la locución y la música recupera su volumen gradualmente.

### En un Teléfono Físico
1. Generá el APK Debug desde Android Studio / AI Studio (`gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`) e instalalo en tu teléfono.
2. Al abrir la app, concedé el permiso de lectura de música (`READ_MEDIA_AUDIO` en Android 13+ o `READ_EXTERNAL_STORAGE` en Android 12 o anterior).
3. Tus canciones descargadas aparecerán en la pestaña **Canciones** junto con su título, artista, álbum, duración y portada (`MediaStore`).
4. Bloqueá la pantalla o cambiá de aplicación: `RadioPlaybackService` (`MediaSessionService`) mantiene la reproducción y las intervenciones del locutor activas en segundo plano con controles multimedia del sistema.

---

## 6. Modelos y Voz Efectivamente Utilizados

Tras consultar `GET https://api.groq.com/openai/v1/models` con la cuenta proporcionada:

| Función | Modelo utilizado / configurado | Detalle en la cuenta actual |
| :--- | :--- | :--- |
| **Generación de guion (LLM)** | `openai/gpt-oss-120b` (con respaldo automático a `openai/gpt-oss-20b` y `qwen/qwen3.8-27b`, o `llama-3.3-70b-versatile` si se habilita en la cuenta) | Activo y verificado en vivo. Genera JSON estructurado con guiones rioplatenses de 5 a 15 s en ~0.7 s. |
| **Text-to-Speech (Voz en español)** | Configurado para `canopylabs/orpheus-v1-spanish` / voz `diana` (configurable mediante `GROQ_TTS_MODEL` y `GROQ_TTS_VOICE` en `backend/.env`) | En el listado `/openai/v1/models` de la clave actual solo aparecen habilitados `canopylabs/orpheus-v1-english` (requiere aceptar términos en consola Groq) y `canopylabs/orpheus-arabic-saudi`. Cumpliendo la regla 4.2, **no se sustituye por voz inglesa**: el backend entrega el guion real generado por Groq e indica a Android usar `demo_locutor.wav` como audio de respaldo hasta que se habilite un modelo/voz en español en la consola de Groq. |
| **Speech-to-Text (Etapa futura)** | `whisper-large-v3-turbo` (`whisper-large-v3` también activo) | Endpoint `/api/stt` e interfaz `ListenerVoiceMessageTranscriber` listos en Android. |

---

## 7. Funcionamiento del Fallback sin Internet o ante Errores de Groq

Si ocurre cualquiera de estas situaciones:
- El teléfono no tiene conexión a Internet (`Sin conexión`).
- El backend está apagado o inaccesible.
- Groq devuelve error HTTP, timeout o JSON inválido (`Error temporal de Groq`).
- El modelo o voz en español de TTS no está habilitado en la cuenta (`Usando audio de respaldo`).

**Comportamiento garantizado:**
1. La música **nunca se detiene ni queda permanentemente atenuada**.
2. Android muestra el estado claro correspondiente (`Sin conexión`, `Usando audio de respaldo` o `Error temporal de Groq`).
3. El segundo reproductor (`voicePlayer`) utiliza automáticamente `demo_locutor.wav` (incluido en `res/raw/demo_locutor.wav` y copiado a almacenamiento interno), ejecutando el mismo ciclo completo de *fade-down* → locución → *fade-up*.

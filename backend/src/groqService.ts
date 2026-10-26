import { BackendConfig, sanitizeErrorMessage } from "./config";
import { InterventionCache } from "./cache";
import {
  GeneratedScriptJson,
  InterventionRequest,
  InterventionResponse,
  SpeechToTextRequest,
  SpeechToTextResponse,
  validateGeneratedScriptJson
} from "./types";

export const RADIO_MIA_SYSTEM_PROMPT = `Sos el locutor masculino principal de Radio Mía, una radio FM argentina personal, moderna, cálida y entretenida.
Hablás en español rioplatense natural (usando "vos", "acá", "seguimos", "acompañándote"), con voz de hombre de radio, buena presencia, cercanía y energía.
No exageres el lunfardo ni uses expresiones forzadas o caricaturescas.
Sonás como un verdadero conductor masculino de radio FM argentina en cabina, nunca como un asistente virtual ni un robot.
No copies frases, nombres, voces ni identidad de Radio Disney ni de ninguna otra emisora existente.
No digas jamás que sos una inteligencia artificial.

REGLA ESTRICTA DE VERACIDAD (PROHIBIDO INVENTAR DATOS):
- Está TERMINANTEMENTE PROHIBIDO inventar datos falsos sobre canciones, artistas, álbumes, años de lanzamiento, fechas, premios, posiciones en rankings, discográficas, integrantes de bandas o historias detrás de la canción.
- Si el artista figura como "Artista Local" o "No especificado", NO menciones ningún artista.
- Si el álbum figura como "Biblioteca del Teléfono", "Biblioteca Local", "Download" o "No especificado", NO menciones el álbum en absoluto.
- Incluso cuando conozcas al artista o la canción, NO cites años específicos ni anécdotas dudosas: enfocate en la compañía de la radio, las sensaciones, el clima musical, la energía del tema y el momento compartido con el oyente.

EXTENSIÓN Y ESTRUCTURA DE LA LOCUCIÓN:
- Tu intervención debe ser EXTENSA, fluida y completa: entre 3 y 5 oraciones bien desarrolladas (entre 50 y 85 palabras, pensada para unos 18 a 25 segundos de aire).
- Estructura ideal:
  1) Entrada cálida de cabina conectando con quien está escuchando Radio Mía y el clima del momento.
  2) Desarrollo sobre la energía, el ritmo o la atmósfera que propone la música que suena.
  3) Presentación natural del tema (y del artista solo si es un artista real confirmado) e invitación a subir el volumen y disfrutarlo.
Devolvé únicamente el JSON pedido.`;

function sanitizeSongMetadata(req: Required<InterventionRequest>): {
  cleanTitle: string;
  cleanArtist: string | null;
  cleanAlbum: string | null;
} {
  const cleanTitle = req.title
    .replace(/\.(mp3|wav|flac|m4a|ogg)$/i, "")
    .replace(/\b(official\s*video|video\s*oficial|lyric\s*video|lyrics|audio\s*oficial|hd|hq|320kbps)\b/gi, "")
    .replace(/[\(\[\{]\s*[\)\]\}]/g, "")
    .replace(/\s{2,}/g, " ")
    .trim() || req.title;

  const genericArtists = new Set([
    "artista local",
    "<unknown>",
    "unknown",
    "desconocido",
    "varios",
    "various artists",
    "audio"
  ]);
  const rawArtist = req.artist.trim();
  const cleanArtist = genericArtists.has(rawArtist.toLowerCase()) ? null : rawArtist;

  const genericAlbums = new Set([
    "biblioteca del teléfono",
    "biblioteca del telefono",
    "biblioteca local",
    "<unknown>",
    "unknown",
    "download",
    "downloads",
    "music",
    "audio",
    "whatsapp audio",
    "sesiones de estudio (libre de derechos)",
    "sesiones de estudio"
  ]);
  const rawAlbum = req.album.trim();
  const cleanAlbum = genericAlbums.has(rawAlbum.toLowerCase()) ? null : rawAlbum;

  return { cleanTitle, cleanArtist, cleanAlbum };
}

export interface GroqAccountModels {
  textModels: string[];
  speechModels: string[];
  spanishSpeechModels: string[];
  transcriptionModels: string[];
}

export class GroqRadioService {
  private readonly config: BackendConfig;
  private readonly cache: InterventionCache;
  private cachedAccountModels: { data: GroqAccountModels; fetchedAt: number } | null = null;

  constructor(config: BackendConfig, cache: InterventionCache) {
    this.config = config;
    this.cache = cache;
  }

  public async discoverModels(): Promise<GroqAccountModels> {
    if (this.cachedAccountModels && Date.now() - this.cachedAccountModels.fetchedAt < 5 * 60 * 1000) {
      return this.cachedAccountModels.data;
    }

    const apiKey = this.config.getGroqApiKey();
    if (!apiKey) {
      throw new Error("MISSING_GROQ_API_KEY");
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.requestTimeoutMs);

    try {
      const resp = await fetch("https://api.groq.com/openai/v1/models", {
        method: "GET",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "User-Agent": "RadioMiaBackend/1.0"
        },
        signal: controller.signal
      });

      if (!resp.ok) {
        throw new Error(`HTTP_${resp.status}`);
      }

      const payload = (await resp.json()) as {
        data?: Array<{
          id: string;
          active?: boolean;
          input_modalities?: string[];
          output_modalities?: string[];
        }>;
      };

      const rawModels = payload.data || [];
      const textModels: string[] = [];
      const speechModels: string[] = [];
      const spanishSpeechModels: string[] = [];
      const transcriptionModels: string[] = [];

      for (const m of rawModels) {
        if (m.active === false) continue;
        const id = m.id;
        const out = m.output_modalities || [];
        if (out.includes("speech") || id.includes("tts") || id.includes("orpheus")) {
          speechModels.push(id);
          const lower = id.toLowerCase();
          const isExplicitNonSpanish =
            lower.includes("english") || lower.includes("arabic") || lower.endsWith("-en");
          if (!isExplicitNonSpanish) {
            spanishSpeechModels.push(id);
          }
        } else if (out.includes("transcription") || id.includes("whisper")) {
          transcriptionModels.push(id);
        } else if (
          out.includes("text") &&
          !id.includes("prompt-guard") &&
          !id.includes("safeguard")
        ) {
          textModels.push(id);
        }
      }

      const result: GroqAccountModels = {
        textModels,
        speechModels,
        spanishSpeechModels,
        transcriptionModels
      };
      this.cachedAccountModels = { data: result, fetchedAt: Date.now() };
      return result;
    } finally {
      clearTimeout(timer);
    }
  }

  public async selectBestTextModel(): Promise<string> {
    try {
      const discovered = await this.discoverModels();
      const activeLlama = discovered.textModels.find((m) =>
        m.toLowerCase().includes("llama")
      );
      if (activeLlama) return activeLlama;

      for (const candidate of this.config.preferredTextModels) {
        if (discovered.textModels.includes(candidate)) {
          return candidate;
        }
      }

      if (discovered.textModels.length > 0) {
        return discovered.textModels[0];
      }
    } catch {
      // Continuar con modelo por defecto
    }
    return "openai/gpt-oss-120b";
  }

  public async generateIntervention(
    req: Required<InterventionRequest>
  ): Promise<InterventionResponse> {
    const apiKey = this.config.getGroqApiKey();
    if (!apiKey) {
      throw new Error("MISSING_GROQ_API_KEY");
    }

    const cacheKey = this.cache.buildKey(req, this.config.promptVersion);
    const cached = this.cache.get(cacheKey);
    if (cached) {
      return cached;
    }

    const { script, modelUsed } = await this.generateScriptWithFallbackModels(req, apiKey);
    const ttsResult = await this.generateSpanishSpeech(script.text, apiKey);

    const response: InterventionResponse = {
      cacheKey,
      cached: false,
      type: script.type,
      text: script.text,
      tone: script.tone,
      estimatedDurationSeconds: script.estimatedDurationSeconds,
      textModelUsed: modelUsed,
      ttsModelUsed: ttsResult.modelUsed,
      ttsVoiceUsed: ttsResult.voiceUsed,
      audioAvailable: ttsResult.audioBase64 !== null,
      audioFormat: "wav",
      audioBase64: ttsResult.audioBase64,
      ttsErrorCode: ttsResult.errorCode,
      ttsStatusMessage: ttsResult.statusMessage
    };

    this.cache.set(cacheKey, response);
    return response;
  }

  private async generateScriptWithFallbackModels(
    req: Required<InterventionRequest>,
    apiKey: string
  ): Promise<{ script: GeneratedScriptJson; modelUsed: string }> {
    const primaryModel = await this.selectBestTextModel();
    const candidateModels = [
      primaryModel,
      ...this.config.preferredTextModels.filter((m) => m !== primaryModel)
    ];

    const { cleanTitle, cleanArtist, cleanAlbum } = sanitizeSongMetadata(req);

    const userPrompt = `Generá una intervención de locutor masculino en vivo para Radio Mía sobre la canción que está sonando:
- Título de la canción: "${cleanTitle}"
- Artista confirmado: ${cleanArtist ? `"${cleanArtist}"` : "No especificado (NO nombres ni inventes ningún artista)"}
- Álbum confirmado: ${cleanAlbum ? `"${cleanAlbum}"` : "No especificado (NO menciones ningún álbum)"}
- Estilo de la radio: ${req.radioStyle}
- Tipo de intervención: ${req.interventionType}

INSTRUCCIONES CLAVE:
1. Que sea una intervención EXTENSA y completa: entre 3 y 5 oraciones fluidas (aproximadamente 55 a 85 palabras, unos 18 a 25 segundos hablados).
2. NO inventes absolutamente ningún dato falso: no digas en qué año salió, no inventes historias de grabación, premios ni datos biográficos. Hablá desde la emoción de la radio, la compañía, el clima del estilo "${req.radioStyle}" y el placer de escuchar "${cleanTitle}"${cleanArtist ? ` con ${cleanArtist}` : ""}.
3. Usá español rioplatense masculino natural, cálido y con presencia de estudio FM.

Devolvé únicamente un objeto JSON válido con esta estructura exacta:
{
  "type": "${req.interventionType}",
  "text": "Locución extensa de 3 a 5 oraciones en español rioplatense, cálida, sin inventar datos falsos...",
  "tone": "energico | calido | nocturno | relajado",
  "estimatedDurationSeconds": 20
}`;

    let lastError = "No se pudo generar el guion con los modelos disponibles.";

    for (const model of candidateModels) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), this.config.requestTimeoutMs);
      try {
        const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": "application/json",
            "User-Agent": "RadioMiaBackend/1.0"
          },
          body: JSON.stringify({
            model,
            messages: [
              { role: "system", content: RADIO_MIA_SYSTEM_PROMPT },
              { role: "user", content: userPrompt }
            ],
            response_format: { type: "json_object" },
            temperature: 0.65,
            max_tokens: 650
          }),
          signal: controller.signal
        });

        if (!res.ok) {
          const errBody = await res.text();
          lastError = sanitizeErrorMessage(`Groq Chat HTTP ${res.status}: ${errBody.slice(0, 180)}`, apiKey);
          continue;
        }

        const data = (await res.json()) as {
          choices?: Array<{ message?: { content?: string } }>;
        };
        const rawContent = data.choices?.[0]?.message?.content?.trim() || "";
        if (!rawContent) {
          lastError = `Respuesta vacía del modelo ${model}`;
          continue;
        }

        let parsed: unknown;
        try {
          parsed = JSON.parse(rawContent);
        } catch {
          const jsonMatch = rawContent.match(/\{[\s\S]*\}/);
          if (!jsonMatch) {
            lastError = `JSON mal formado devuelto por ${model}`;
            continue;
          }
          parsed = JSON.parse(jsonMatch[0]);
        }

        const validation = validateGeneratedScriptJson(
          parsed,
          req.interventionType,
          req.maxDurationSeconds
        );
        if (!validation.valid) {
          lastError = validation.error;
          continue;
        }

        return { script: validation.script, modelUsed: model };
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        lastError = sanitizeErrorMessage(msg, apiKey);
      } finally {
        clearTimeout(timer);
      }
    }

    throw new Error(`GROQ_SCRIPT_ERROR: ${lastError}`);
  }

  /**
   * Intenta sintetizar la voz masculina en español mediante el endpoint Text-to-Speech de Groq (/openai/v1/audio/speech).
   * Usa las voces masculinas configuradas (mateo, javier, carlos, troy, daniel, austin).
   */
  public async generateSpanishSpeech(
    text: string,
    apiKey: string
  ): Promise<{
    audioBase64: string | null;
    modelUsed: string;
    voiceUsed: string;
    errorCode?: string;
    statusMessage: string;
  }> {
    let spanishModels: string[] = [];
    try {
      const discovered = await this.discoverModels();
      spanishModels = discovered.spanishSpeechModels;
    } catch {
      spanishModels = [];
    }

    const modelsToTry =
      spanishModels.length > 0
        ? spanishModels
        : [this.config.preferredSpanishTtsModels[0]];
    const primaryMaleVoice = this.config.preferredSpanishVoices[0] || "mateo";

    let lastErrorCode = "spanish_tts_model_not_available";
    let lastErrorDetail =
      `Modelo TTS (${modelsToTry[0]} / voz masculina '${primaryMaleVoice}') no habilitado directamente en Groq; Android sintetiza la voz masculina en español para el guion.`;

    for (const model of modelsToTry) {
      for (const maleVoice of this.config.preferredSpanishVoices.slice(0, 2)) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.config.requestTimeoutMs);
        try {
          const res = await fetch("https://api.groq.com/openai/v1/audio/speech", {
            method: "POST",
            headers: {
              Authorization: `Bearer ${apiKey}`,
              "Content-Type": "application/json",
              "User-Agent": "RadioMiaBackend/1.0"
            },
            body: JSON.stringify({
              model,
              input: text,
              voice: maleVoice,
              response_format: "wav"
            }),
            signal: controller.signal
          });

          if (!res.ok) {
            lastErrorCode = `groq_tts_http_${res.status}`;
            lastErrorDetail = sanitizeErrorMessage(
              `TTS (${model} / voz masculina '${maleVoice}') devolvió ${res.status}. Sintetizando voz masculina en Android.`,
              apiKey
            );
            continue;
          }

          const arrayBuf = await res.arrayBuffer();
          const buffer = Buffer.from(arrayBuf);

          if (buffer.length <= 44 || buffer.subarray(0, 4).toString("ascii") !== "RIFF") {
            lastErrorCode = "corrupt_or_empty_audio";
            lastErrorDetail = "El audio devuelto por Groq TTS no tenía cabecera WAV válida.";
            continue;
          }

          return {
            audioBase64: buffer.toString("base64"),
            modelUsed: model,
            voiceUsed: maleVoice,
            statusMessage: `Voz masculina generada con Groq TTS (${model} • ${maleVoice})`
          };
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          lastErrorCode = "tts_network_or_timeout";
          lastErrorDetail = sanitizeErrorMessage(msg, apiKey);
        } finally {
          clearTimeout(timer);
        }
      }
    }

    return {
      audioBase64: null,
      modelUsed: modelsToTry[0] || "canopylabs/orpheus-v1-spanish",
      voiceUsed: primaryMaleVoice,
      errorCode: lastErrorCode,
      statusMessage: lastErrorDetail
    };
  }

  public async transcribeListenerAudio(
    req: SpeechToTextRequest
  ): Promise<SpeechToTextResponse> {
    const apiKey = this.config.getGroqApiKey();
    if (!apiKey) {
      throw new Error("MISSING_GROQ_API_KEY");
    }
    if (!req.audioBase64 || req.audioBase64.trim().length < 16) {
      throw new Error("INVALID_AUDIO_PAYLOAD");
    }

    const audioBuffer = Buffer.from(req.audioBase64, "base64");
    const formData = new FormData();
    const blob = new Blob([audioBuffer], { type: "audio/wav" });
    formData.append("file", blob, "mensaje_oyente.wav");
    formData.append("model", this.config.sttModel);
    formData.append("language", req.language || "es");

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.requestTimeoutMs);
    try {
      const res = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "User-Agent": "RadioMiaBackend/1.0"
        },
        body: formData,
        signal: controller.signal
      });

      if (!res.ok) {
        throw new Error(`STT_HTTP_${res.status}`);
      }

      const data = (await res.json()) as { text?: string };
      return {
        text: (data.text || "").trim(),
        modelUsed: this.config.sttModel
      };
    } finally {
      clearTimeout(timer);
    }
  }
}

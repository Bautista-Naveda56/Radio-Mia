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

export const RADIO_MIA_SYSTEM_PROMPT = `Sos el locutor de Radio Mía, una radio argentina personal, moderna y entretenida.
Hablás en español rioplatense natural, con calidez y energía.
No exageres el lunfardo ni uses expresiones forzadas.
Sonás como un locutor de radio pop argentina, no como un asistente virtual.
Tus intervenciones son breves, naturales y variadas.
No copies frases, nombres, voces ni identidad de Radio Disney ni de ninguna otra radio.
No digas que sos una inteligencia artificial.
No inventes datos sobre canciones, artistas, álbumes, fechas o hechos históricos.
Si no hay información confiable, hacé una presentación genérica.
Respetá la duración máxima solicitada.
Devolvé únicamente el JSON pedido.`;

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
          // NUNCA clasificar un modelo explícitamente inglés o árabe como voz en español
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
      // 1. Preferir cualquier modelo Llama conversacional activo en la cuenta
      const activeLlama = discovered.textModels.find((m) =>
        m.toLowerCase().includes("llama")
      );
      if (activeLlama) return activeLlama;

      // 2. Seguir el orden de preferencia configurado si está en la lista activa
      for (const candidate of this.config.preferredTextModels) {
        if (discovered.textModels.includes(candidate)) {
          return candidate;
        }
      }

      // 3. Si hay algún otro modelo de texto activo en la cuenta
      if (discovered.textModels.length > 0) {
        return discovered.textModels[0];
      }
    } catch {
      // Continuar con lista de prioridad por defecto si falla la consulta de listado
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

    // Paso 3: Generar el guion breve del locutor con un modelo disponible en la cuenta de Groq
    const { script, modelUsed } = await this.generateScriptWithFallbackModels(req, apiKey);

    // Paso 4: Enviar el texto al endpoint Text-to-Speech de Groq usando una voz en español disponible
    // (No sustituir automáticamente por una voz inglesa)
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

    const userPrompt = `Generá una intervención de radio breve (entre 5 y ${req.maxDurationSeconds} segundos al hablarse) para esta canción en reproducción:
- Título: ${req.title}
- Artista: ${req.artist}
- Álbum: ${req.album}
- Género: ${req.genre}
- Duración de la canción: ${req.durationSeconds} segundos
- Estilo de radio elegido: ${req.radioStyle}
- Tipo de intervención solicitada: ${req.interventionType}
- Duración máxima de locución: ${req.maxDurationSeconds} segundos

Devolvé únicamente un objeto JSON válido con esta estructura exacta:
{
  "type": "${req.interventionType}",
  "text": "Texto breve y natural en español rioplatense listo para ser leído al aire",
  "tone": "energico | calido | nocturno | relajado",
  "estimatedDurationSeconds": 8
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
            temperature: 0.7,
            max_tokens: 280
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
   * Intenta sintetizar la voz en español mediante el endpoint Text-to-Speech de Groq (/openai/v1/audio/speech).
   * Cumple estrictamente la regla 4.2:
   * - Revisa los modelos/voces en español disponibles en la cuenta.
   * - NUNCA sustituye la voz en español por un modelo/voz en inglés (como canopylabs/orpheus-v1-english).
   * - Si el modelo o la voz en español no están habilitados en la cuenta actual, devuelve un código de error claro
   *   para que Android reproduzca demo_locutor.wav como respaldo mientras muestra el guion generado.
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
    const primaryVoice = this.config.preferredSpanishVoices[0] || "diana";

    let lastErrorCode = "spanish_tts_model_not_available";
    let lastErrorDetail =
      "La cuenta actual de Groq sólo lista modelos TTS en inglés/árabe (canopylabs/orpheus-v1-english, canopylabs/orpheus-arabic-saudi). No se sustituye por voz inglesa; usando demo_locutor.wav.";

    for (const model of modelsToTry) {
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
            voice: primaryVoice,
            response_format: "wav"
          }),
          signal: controller.signal
        });

        if (!res.ok) {
          const errText = await res.text();
          lastErrorCode = `groq_tts_http_${res.status}`;
          lastErrorDetail = sanitizeErrorMessage(
            `TTS (${model} / voz '${primaryVoice}') no disponible en Groq (${res.status}). Usando demo_locutor.wav como respaldo.`,
            apiKey
          );
          continue;
        }

        const arrayBuf = await res.arrayBuffer();
        const buffer = Buffer.from(arrayBuf);

        // Validar que el audio no esté vacío ni corrupto (encabezado RIFF/WAVE mínimo > 44 bytes)
        if (buffer.length <= 44 || buffer.subarray(0, 4).toString("ascii") !== "RIFF") {
          lastErrorCode = "corrupt_or_empty_audio";
          lastErrorDetail = "El audio devuelto por Groq TTS estaba vacío o no tenía cabecera WAV válida.";
          continue;
        }

        return {
          audioBase64: buffer.toString("base64"),
          modelUsed: model,
          voiceUsed: primaryVoice,
          statusMessage: `Voz en español generada con Groq TTS (${model} • ${primaryVoice})`
        };
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        lastErrorCode = "tts_network_or_timeout";
        lastErrorDetail = sanitizeErrorMessage(msg, apiKey);
      } finally {
        clearTimeout(timer);
      }
    }

    return {
      audioBase64: null,
      modelUsed: modelsToTry[0] || "canopylabs/orpheus-v1-spanish",
      voiceUsed: primaryVoice,
      errorCode: lastErrorCode,
      statusMessage: lastErrorDetail
    };
  }

  /**
   * Interfaz preparada para futura transcripción de mensajes de voz de oyentes (Sección 4.3).
   */
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

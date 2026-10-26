import path from "node:path";
import dotenv from "dotenv";

// Carga variables desde backend/.env sin imprimir ni exponer secretos
dotenv.config({ path: path.resolve(__dirname, "..", ".env") });

export interface BackendConfig {
  port: number;
  hasGroqApiKey: boolean;
  getGroqApiKey: () => string;
  preferredTextModels: string[];
  preferredSpanishTtsModels: string[];
  preferredSpanishVoices: string[];
  sttModel: string;
  requestTimeoutMs: number;
  promptVersion: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BackendConfig {
  const customTextModel = env.GROQ_TEXT_MODEL?.trim();
  const customTtsModel = env.GROQ_TTS_MODEL?.trim();
  const customTtsVoice = env.GROQ_TTS_VOICE?.trim();

  // Orden de prioridad para generación de guion según disponibilidad real en la cuenta Groq
  const defaultTextModels = [
    "llama-3.3-70b-versatile",
    "llama-3.1-8b-instant",
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b",
    "qwen/qwen3.8-27b"
  ];

  // Modelos TTS en Groq priorizando español
  const defaultSpanishTtsModels = [
    "canopylabs/orpheus-v1-spanish",
    "canopylabs/orpheus-spanish",
    "playai-tts-es",
    "playai-tts"
  ];

  // Voces masculinas de locutor en Groq (Orpheus / PlayAI)
  const defaultMaleVoices = [
    "mateo",
    "javier",
    "carlos",
    "diego",
    "troy",
    "daniel",
    "austin"
  ];

  return {
    port: Number(env.RADIO_MIA_BACKEND_PORT || env.PORT || 3000),
    hasGroqApiKey: Boolean(env.GROQ_API_KEY && env.GROQ_API_KEY.trim().length > 0),
    getGroqApiKey: () => (env.GROQ_API_KEY || "").trim(),
    preferredTextModels: customTextModel
      ? [customTextModel, ...defaultTextModels.filter((m) => m !== customTextModel)]
      : defaultTextModels,
    preferredSpanishTtsModels: customTtsModel
      ? [customTtsModel, ...defaultSpanishTtsModels.filter((m) => m !== customTtsModel)]
      : defaultSpanishTtsModels,
    preferredSpanishVoices: customTtsVoice
      ? [customTtsVoice, ...defaultMaleVoices.filter((v) => v !== customTtsVoice)]
      : defaultMaleVoices,
    sttModel: env.GROQ_STT_MODEL?.trim() || "whisper-large-v3-turbo",
    requestTimeoutMs: Number(env.GROQ_TIMEOUT_MS || 11000),
    promptVersion: "v2.0-male-extended-verified"
  };
}

/**
 * Sanitiza cualquier mensaje de error para garantizar que GROQ_API_KEY nunca aparezca en logs ni respuestas.
 */
export function sanitizeErrorMessage(message: string, apiKey?: string): string {
  let clean = message;
  if (apiKey && apiKey.length > 4) {
    clean = clean.split(apiKey).join("[REDACTED]");
  }
  clean = clean.replace(/gsk_[A-Za-z0-9_\-]+/g, "[REDACTED_GROQ_KEY]");
  return clean;
}

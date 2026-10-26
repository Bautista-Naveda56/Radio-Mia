export type InterventionType =
  | "presentacion"
  | "continuidad"
  | "cambio_clima"
  | "dato_curioso"
  | "mensaje_oyente";

export type RadioStyle =
  | "Radio pop argentina"
  | "Radio nocturna"
  | "Rock argentino"
  | "Radio energética"
  | "Radio tranquila";

export interface InterventionRequest {
  title: string;
  artist: string;
  album?: string;
  genre?: string;
  durationSeconds?: number;
  radioStyle?: string;
  interventionType?: InterventionType;
  maxDurationSeconds?: number;
}

export interface GeneratedScriptJson {
  type: InterventionType;
  text: string;
  tone: string;
  estimatedDurationSeconds: number;
}

export interface InterventionResponse {
  cacheKey: string;
  cached: boolean;
  type: InterventionType;
  text: string;
  tone: string;
  estimatedDurationSeconds: number;
  textModelUsed: string;
  ttsModelUsed: string;
  ttsVoiceUsed: string;
  audioAvailable: boolean;
  audioFormat: "wav";
  audioBase64: string | null;
  ttsErrorCode?: string;
  ttsStatusMessage: string;
}

export interface SpeechToTextRequest {
  audioBase64: string;
  language?: string;
}

export interface SpeechToTextResponse {
  text: string;
  modelUsed: string;
}

export const ALLOWED_INTERVENTION_TYPES: ReadonlySet<string> = new Set([
  "presentacion",
  "continuidad",
  "cambio_clima",
  "dato_curioso",
  "mensaje_oyente"
]);

export const MAX_FIELD_LENGTH = 240;

export function validateInterventionRequest(
  body: unknown
): { valid: true; data: Required<InterventionRequest> } | { valid: false; error: string; code: string } {
  if (!body || typeof body !== "object") {
    return { valid: false, code: "invalid_body", error: "El cuerpo de la solicitud debe ser un objeto JSON." };
  }

  const raw = body as Record<string, unknown>;
  const title = typeof raw.title === "string" ? raw.title.trim() : "";
  const artist = typeof raw.artist === "string" ? raw.artist.trim() : "Artista Local";
  const album = typeof raw.album === "string" ? raw.album.trim() : "Biblioteca Local";
  const genre = typeof raw.genre === "string" ? raw.genre.trim() : "Música variada";
  const radioStyle = typeof raw.radioStyle === "string" && raw.radioStyle.trim().length > 0
    ? raw.radioStyle.trim()
    : "Radio pop argentina";
  const interventionTypeRaw = typeof raw.interventionType === "string" && raw.interventionType.trim().length > 0
    ? raw.interventionType.trim()
    : "presentacion";

  if (!title) {
    return { valid: false, code: "missing_title", error: "El campo 'title' es obligatorio." };
  }

  if (
    title.length > MAX_FIELD_LENGTH ||
    artist.length > MAX_FIELD_LENGTH ||
    album.length > MAX_FIELD_LENGTH ||
    genre.length > MAX_FIELD_LENGTH ||
    radioStyle.length > MAX_FIELD_LENGTH
  ) {
    return {
      valid: false,
      code: "input_too_long",
      error: `Los metadatos de entrada superan el límite máximo permitido (${MAX_FIELD_LENGTH} caracteres).`
    };
  }

  if (!ALLOWED_INTERVENTION_TYPES.has(interventionTypeRaw)) {
    return {
      valid: false,
      code: "invalid_intervention_type",
      error: `Tipo de intervención inválido: ${interventionTypeRaw}.`
    };
  }

  const durationSeconds = typeof raw.durationSeconds === "number" && Number.isFinite(raw.durationSeconds)
    ? Math.max(1, Math.min(7200, Math.round(raw.durationSeconds)))
    : 180;

  const maxDurationSeconds = typeof raw.maxDurationSeconds === "number" && Number.isFinite(raw.maxDurationSeconds)
    ? Math.max(5, Math.min(15, Math.round(raw.maxDurationSeconds)))
    : 12;

  return {
    valid: true,
    data: {
      title,
      artist: artist || "Artista Local",
      album: album || "Biblioteca Local",
      genre: genre || "Pop / Rock",
      durationSeconds,
      radioStyle,
      interventionType: interventionTypeRaw as InterventionType,
      maxDurationSeconds
    }
  };
}

export function validateGeneratedScriptJson(
  parsed: unknown,
  fallbackType: InterventionType,
  maxDurationSeconds: number
): { valid: true; script: GeneratedScriptJson } | { valid: false; error: string } {
  if (!parsed || typeof parsed !== "object") {
    return { valid: false, error: "La respuesta del modelo no es un objeto JSON válido." };
  }

  const obj = parsed as Record<string, unknown>;
  const rawText = typeof obj.text === "string" ? obj.text.trim() : "";
  if (!rawText || rawText.length < 4) {
    return { valid: false, error: "El guion generado está vacío o es demasiado corto." };
  }
  if (rawText.length > 420) {
    return { valid: false, error: "El guion generado excede la longitud máxima permitida para una intervención breve." };
  }

  const rawType = typeof obj.type === "string" && ALLOWED_INTERVENTION_TYPES.has(obj.type)
    ? (obj.type as InterventionType)
    : fallbackType;

  const tone = typeof obj.tone === "string" && obj.tone.trim().length > 0
    ? obj.tone.trim()
    : "calido";

  const estimatedRaw = typeof obj.estimatedDurationSeconds === "number" && Number.isFinite(obj.estimatedDurationSeconds)
    ? Math.round(obj.estimatedDurationSeconds)
    : Math.max(5, Math.min(maxDurationSeconds, Math.ceil(rawText.split(/\s+/).length / 2.6)));

  const estimatedDurationSeconds = Math.max(4, Math.min(15, estimatedRaw));

  return {
    valid: true,
    script: {
      type: rawType,
      text: rawText,
      tone,
      estimatedDurationSeconds
    }
  };
}

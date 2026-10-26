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

export const RADIO_MIA_SYSTEM_PROMPT = `Sos el locutor masculino principal de Radio Mía, una radio FM argentina personal, moderna, cálida y con profundo conocimiento musical.
Hablás en español rioplatense natural (usando "vos", "acá", "seguimos", "mirá este dato", "escuchá esto"), con voz de hombre de radio, buena presencia, cercanía y fluidez humana.
No exageres el lunfardo ni uses expresiones forzadas o caricaturescas.
Sonás como un verdadero conductor masculino de radio FM argentina en cabina, nunca como un asistente virtual ni un robot.
No copies frases, nombres, voces ni identidad de Radio Disney ni de ninguna otra emisora existente.
No digas jamás que sos una inteligencia artificial.

REGLAS SEGÚN EL TIPO DE INTERVENCIÓN:
1. CUANDO EL TIPO ES "dato_curioso" (MODO INVESTIGACIÓN MUSICAL Y TRIVIA REAL):
   - Tenés PROHIBIDO tirar un saludo genérico o hablar solo del clima.
   - DEBÉS contar un DATO CURIOSO REAL, CONCRETO Y ESPECÍFICO sobre la canción o el artista (por ejemplo: quién la compuso, en qué estudio o ciudad se grabó, de qué álbum forma parte y en qué año salió, un detalle de sus instrumentos/letra, una anécdota real de grabación o un récord real del tema).
   - Usá la información verificada de investigación (Wikipedia / catálogo musical) provista en el prompt y tu conocimiento enciclopédico real sobre esa canción.
   - Si la canción tiene varios datos reales interesantes, elegí UNO al azar que sorprenda al oyente y contalo con onda de locutor de radio FM.
2. REGLA DE VERACIDAD:
   - Usá únicamente hechos reales y verificados sobre la canción y el artista; no inventes premios falsos ni menciones etiquetas técnicas del teléfono como "Artista Local", "Biblioteca del Teléfono" o "Download".

DURACIÓN MÁXIMA 15 SEGUNDOS Y CADENCIA HUMANA:
- Tu intervención debe durar como MÁXIMO 15 SEGUNDOS al hablarse (entre 2 y 3 oraciones fluidas, aproximadamente 28 a 42 palabras).
- Escribí con puntuación expresiva de locución radial (comas naturales, puntos suspensivos "..." para pausas breves de respiración y entonación cálida) para que la síntesis de voz suene 100% natural, orgánica y nada robótica.
Devolvé únicamente el JSON pedido.`;

export interface SongResearchResult {
  cleanTitle: string;
  cleanArtist: string | null;
  cleanAlbum: string | null;
  verifiedYear: string | null;
  verifiedGenre: string | null;
  wikipediaFacts: string | null;
}

const TRIVIA_ANGLES = [
  "el origen o composición de la canción (quién la escribió, cómo nació la idea o qué inspira su letra)",
  "el detalle de grabación o producción (en qué estudio, año o álbum se grabó y qué sonido o instrumento la destaca)",
  "el impacto cultural, hito o curiosidad poco conocida de esta canción en la carrera del artista",
  "un dato específico sobre la historia del álbum al que pertenece o un detalle musical distintivo del tema"
];

function sanitizeSongMetadata(req: Required<InterventionRequest>): {
  cleanTitle: string;
  cleanArtist: string | null;
  cleanAlbum: string | null;
} {
  let rawTitle = req.title
    .replace(/\.(mp3|wav|flac|m4a|ogg)$/i, "")
    .replace(/\b(official\s*video|video\s*oficial|lyric\s*video|lyrics|audio\s*oficial|hd|hq|320kbps|remastered|remasterizado)\b/gi, "")
    .replace(/[\(\[\{]\s*[\)\]\}]/g, "")
    .replace(/\s{2,}/g, " ")
    .trim() || req.title;

  const genericArtists = new Set([
    "artista local",
    "no especificado",
    "<unknown>",
    "unknown",
    "desconocido",
    "varios",
    "various artists",
    "audio"
  ]);
  const rawArtist = req.artist.trim();
  let cleanArtist = genericArtists.has(rawArtist.toLowerCase()) ? null : rawArtist;

  // Si el archivo MP3 tiene formato "Artista - Canción" en el título y no hay artista en MediaStore, extraerlo automáticamente
  if (!cleanArtist && rawTitle.includes(" - ")) {
    const parts = rawTitle.split(" - ").map((p) => p.trim()).filter(Boolean);
    if (parts.length >= 2) {
      cleanArtist = parts[0];
      rawTitle = parts.slice(1).join(" - ");
    }
  }

  const genericAlbums = new Set([
    "biblioteca del teléfono",
    "biblioteca del telefono",
    "biblioteca local",
    "no especificado",
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

  return { cleanTitle: rawTitle, cleanArtist, cleanAlbum };
}

function normalizeForMatch(s: string): string {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, "")
    .trim();
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
  private readonly researchCache = new Map<string, SongResearchResult>();

  constructor(config: BackendConfig, cache: InterventionCache) {
    this.config = config;
    this.cache = cache;
  }

  /**
   * Investiga en tiempo real la canción usando iTunes Search API (catálogo AR) + Wikipedia en Español
   * para obtener artista real, álbum, año de lanzamiento y extractos enciclopédicos verificados.
   */
  public async researchSongContext(req: Required<InterventionRequest>): Promise<SongResearchResult> {
    const { cleanTitle, cleanArtist, cleanAlbum } = sanitizeSongMetadata(req);
    const cacheKey = `${cleanTitle.toLowerCase()}|${(cleanArtist || "").toLowerCase()}`;
    const existing = this.researchCache.get(cacheKey);
    if (existing) {
      return existing;
    }

    let resolvedArtist = cleanArtist;
    let resolvedAlbum = cleanAlbum;
    let verifiedYear: string | null = null;
    let verifiedGenre: string | null = null;
    let wikipediaFacts: string | null = null;

    const searchQuery = resolvedArtist ? `${cleanTitle} ${resolvedArtist}` : cleanTitle;

    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2800);

      try {
        // 1. Buscar metadatos verificados en el catálogo musical (iTunes AR)
        const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(searchQuery)}&entity=song&limit=3&country=AR`;
        const itunesPromise = fetch(itunesUrl, {
          headers: { "User-Agent": "RadioMiaBackend/1.0" },
          signal: controller.signal
        })
          .then((r) => (r.ok ? r.json() : null))
          .catch(() => null);

        // 2. Buscar artículos enciclopédicos en Wikipedia en Español sobre la canción y el artista
        const wikiQuery = resolvedArtist
          ? `"${cleanTitle}" ${resolvedArtist}`
          : `${cleanTitle} canción`;
        const wikiSearchUrl = `https://es.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(wikiQuery)}&utf8=1&format=json&srlimit=3`;
        const wikiPromise = fetch(wikiSearchUrl, {
          headers: { "User-Agent": "RadioMiaBackend/1.0" },
          signal: controller.signal
        })
          .then((r) => (r.ok ? r.json() : null))
          .catch(() => null);

        const [itunesData, wikiSearchData] = (await Promise.all([itunesPromise, wikiPromise])) as [
          {
            results?: Array<{
              trackName?: string;
              artistName?: string;
              collectionName?: string;
              releaseDate?: string;
              primaryGenreName?: string;
            }>;
          } | null,
          { query?: { search?: Array<{ title?: string }> } } | null
        ];

        const normTargetTitle = normalizeForMatch(cleanTitle);
        const matchedTrack = (itunesData?.results || []).find((t) => {
          const normCandidate = normalizeForMatch(t.trackName || "");
          return (
            normCandidate.includes(normTargetTitle) ||
            normTargetTitle.includes(normCandidate)
          );
        });

        if (matchedTrack) {
          if (!resolvedArtist && matchedTrack.artistName) {
            resolvedArtist = matchedTrack.artistName;
          }
          if (!resolvedAlbum && matchedTrack.collectionName) {
            resolvedAlbum = matchedTrack.collectionName;
          }
          if (matchedTrack.releaseDate && matchedTrack.releaseDate.length >= 4) {
            verifiedYear = matchedTrack.releaseDate.slice(0, 4);
          }
          if (matchedTrack.primaryGenreName) {
            verifiedGenre = matchedTrack.primaryGenreName;
          }
        }

        const wikiTitles = (wikiSearchData?.query?.search || [])
          .map((s) => s.title)
          .filter((t): t is string => Boolean(t))
          .slice(0, 2);

        if (wikiTitles.length > 0) {
          const extractUrl = `https://es.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&titles=${encodeURIComponent(wikiTitles.join("|"))}&format=json`;
          const extractRes = await fetch(extractUrl, {
            headers: { "User-Agent": "RadioMiaBackend/1.0" },
            signal: controller.signal
          }).catch(() => null);

          if (extractRes && extractRes.ok) {
            const extractJson = (await extractRes.json()) as {
              query?: { pages?: Record<string, { title?: string; extract?: string }> };
            };
            const snippets: string[] = [];
            for (const page of Object.values(extractJson.query?.pages || {})) {
              const ext = (page.extract || "").replace(/\s+/g, " ").trim();
              if (ext.length > 30) {
                snippets.push(`[${page.title}]: ${ext.slice(0, 480)}`);
              }
            }
            if (snippets.length > 0) {
              wikipediaFacts = snippets.join("\n");
            }
          }
        }
      } finally {
        clearTimeout(timer);
      }
    } catch {
      // Continuar de forma segura si no hay red externa disponible
    }

    const result: SongResearchResult = {
      cleanTitle,
      cleanArtist: resolvedArtist,
      cleanAlbum: resolvedAlbum,
      verifiedYear,
      verifiedGenre,
      wikipediaFacts
    };

    if (this.researchCache.size >= 100) {
      const oldestKey = this.researchCache.keys().next().value;
      if (oldestKey) this.researchCache.delete(oldestKey);
    }
    this.researchCache.set(cacheKey, result);
    return result;
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
          if (!lower.includes("arabic")) {
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
      for (const candidate of this.config.preferredTextModels) {
        if (discovered.textModels.includes(candidate)) {
          return candidate;
        }
      }

      const active120b = discovered.textModels.find((m) => m.includes("120b"));
      if (active120b) return active120b;

      const activeLlama = discovered.textModels.find((m) =>
        m.toLowerCase().includes("llama")
      );
      if (activeLlama) return activeLlama;

      if (discovered.textModels.length > 0) {
        return discovered.textModels[0];
      }
    } catch {
      // Continuar con el modelo más avanzado por defecto
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

    const isDatoCurioso = req.interventionType === "dato_curioso";
    const baseCacheKey = this.cache.buildKey(req, this.config.promptVersion);
    const cacheKey = isDatoCurioso
      ? `${baseCacheKey}_${Math.floor(Math.random() * 10000)}`
      : baseCacheKey;

    if (!isDatoCurioso) {
      const cached = this.cache.get(cacheKey);
      if (cached) {
        return cached;
      }
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

    if (!isDatoCurioso) {
      this.cache.set(cacheKey, response);
    }
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

    const research = await this.researchSongContext(req);
    const { cleanTitle, cleanArtist, cleanAlbum, verifiedYear, verifiedGenre, wikipediaFacts } = research;
    const isDatoCurioso = req.interventionType === "dato_curioso";
    const randomTriviaAngle = TRIVIA_ANGLES[Math.floor(Math.random() * TRIVIA_ANGLES.length)];

    const researchBlock = [
      `- Título de la canción: "${cleanTitle}"`,
      `- Artista verificado: ${cleanArtist ? `"${cleanArtist}"` : "No especificado en el archivo (si reconocés con total certeza de qué canción famosa se trata por su título, podés nombrar a su autor real)"}`,
      `- Álbum verificado: ${cleanAlbum ? `"${cleanAlbum}"` : "No especificado"}`,
      verifiedYear ? `- Año de lanzamiento verificado: ${verifiedYear}` : null,
      verifiedGenre ? `- Género de catálogo: ${verifiedGenre}` : null,
      wikipediaFacts ? `\nDATOS ENCICLOPÉDICOS INVESTIGADOS EN TIEMPO REAL (WIKIPEDIA):\n${wikipediaFacts}` : null
    ]
      .filter(Boolean)
      .join("\n");

    const specificInstruction = isDatoCurioso
      ? `MODO DATO CURIOSO REAL (OBLIGATORIO):
1. El oyente pidió específicamente un "DATO CURIOSO" sobre "${cleanTitle}"${cleanArtist ? ` de ${cleanArtist}` : ""}.
2. PROHIBIDO dar un mensaje genérico de bienvenida o hablar solo de "qué linda música".
3. DEBÉS contar una curiosidad real, concreta y específica sobre esta canción o su artista enfocándote hoy en: ${randomTriviaAngle}.
4. Apoyate en los DATOS ENCICLOPÉDICOS INVESTIGADOS arriba y en tu conocimiento real sobre "${cleanTitle}"${cleanArtist ? ` (${cleanArtist})` : ""}. (Si es una pista instrumental de estudio de Buenos Aires, contá una curiosidad real sobre la historia musical nocturna porteña o del instrumento protagonista).
5. Duración máxima: 15 segundos hablados (2 a 3 oraciones dinámicas, entre 28 y 42 palabras).`
      : `INSTRUCCIONES CLAVE:
1. DURACIÓN MÁXIMA 15 SEGUNDOS: Escribí entre 2 y 3 oraciones dinámicas y cálidas (entre 26 y 40 palabras como máximo, para que hablada dure entre 10 y 15 segundos).
2. VERACIDAD: Usá los datos verificados de arriba sin inventar información falsa.
3. FLUIDEZ HUMANA: Usá español rioplatense masculino natural, con comas y pausas suaves para que la voz suene expresiva y humana.`;

    const userPrompt = `Generá una intervención de locutor masculino en vivo para Radio Mía sobre la canción que está sonando:
${researchBlock}
- Estilo de la radio: ${req.radioStyle}
- Tipo de intervención: ${req.interventionType}
- Duración máxima permitida: ${Math.min(15, req.maxDurationSeconds)} segundos

${specificInstruction}

Devolvé únicamente un objeto JSON válido con esta estructura exacta:
{
  "type": "${req.interventionType}",
  "text": "${isDatoCurioso ? "Dato curioso real, concreto y atrapante sobre la canción en 2 a 3 oraciones (máximo 15 segundos)..." : "Locución de 2 a 3 oraciones (máximo 15 segundos) en español rioplatense..."}",
  "tone": "energico | calido | nocturno | relajado",
  "estimatedDurationSeconds": 12
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
            temperature: isDatoCurioso ? 0.75 : 0.65,
            max_tokens: 4096
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
   * Intenta sintetizar la voz masculina neural en Groq (/openai/v1/audio/speech)
   * usando Orpheus V1 (canopylabs/orpheus-v1-english / canopylabs/orpheus-v1-spanish)
   * con voces masculinas expresivas (troy, daniel, austin, mateo).
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
    let discoveredSpeechModels: string[] = [];
    try {
      const discovered = await this.discoverModels();
      discoveredSpeechModels = discovered.spanishSpeechModels;
    } catch {
      discoveredSpeechModels = [];
    }

    const modelsToTry =
      discoveredSpeechModels.length > 0
        ? discoveredSpeechModels
        : [this.config.preferredSpanishTtsModels[0]];
    const primaryMaleVoice = this.config.preferredSpanishVoices[0] || "troy";

    let lastErrorCode = "groq_orpheus_tts_unavailable";
    let lastErrorDetail =
      `Usando síntesis neural masculina de alta definición en el dispositivo para el guion de ${modelsToTry[0]}.`;

    for (const model of modelsToTry) {
      const voicesForModel = model.includes("english")
        ? ["troy", "daniel", "austin"]
        : this.config.preferredSpanishVoices.slice(0, 3);

      for (const maleVoice of voicesForModel) {
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
            const errText = await res.text();
            lastErrorCode = `groq_tts_http_${res.status}`;
            if (errText.includes("model_terms_required")) {
              lastErrorDetail =
                `Aceptá los términos de ${model} en console.groq.com/playground para audio directo de Orpheus; mientras tanto se usa voz neural masculina HD en Android.`;
              break;
            }
            lastErrorDetail = sanitizeErrorMessage(
              `TTS (${model} / '${maleVoice}') devolvió ${res.status}. Sintetizando voz neural masculina HD en Android.`,
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
            statusMessage: `Voz neural masculina generada con Groq Orpheus (${model} • ${maleVoice})`
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
      modelUsed: modelsToTry[0] || "canopylabs/orpheus-v1-english",
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

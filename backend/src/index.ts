import http from "node:http";
import { loadConfig, sanitizeErrorMessage } from "./config";
import { InterventionCache } from "./cache";
import { GroqRadioService } from "./groqService";
import { validateInterventionRequest } from "./types";

const config = loadConfig();
const cache = new InterventionCache();
const groqService = new GroqRadioService(config, cache);

function sendJson(res: http.ServerResponse, statusCode: number, payload: Record<string, unknown>): void {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
  });
  res.end(body);
}

function readJsonBody(req: http.IncomingMessage, maxBytes = 64 * 1024): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let total = 0;

    req.on("data", (chunk: Buffer) => {
      total += chunk.length;
      if (total > maxBytes) {
        reject(new Error("PAYLOAD_TOO_LARGE"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8").trim();
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error("MALFORMED_JSON"));
      }
    });

    req.on("error", (err) => reject(err));
  });
}

export function createRadioMiaServer(
  customConfig = config,
  customService = groqService,
  customCache = cache
): http.Server {
  return http.createServer(async (req, res) => {
    const method = req.method || "GET";
    const url = (req.url || "/").split("?")[0];

    if (method === "OPTIONS") {
      res.writeHead(204, {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type"
      });
      res.end();
      return;
    }

    if (method === "GET" && (url === "/health" || url === "/api/status")) {
      const selectedModel = customConfig.hasGroqApiKey
        ? await customService.selectBestTextModel().catch(() => customConfig.preferredTextModels[0])
        : "unconfigured";

      sendJson(res, 200, {
        status: "ok",
        service: "Radio Mía Backend",
        groqConfigured: customConfig.hasGroqApiKey,
        activeTextModel: selectedModel,
        preferredSpanishTtsModel: customConfig.preferredSpanishTtsModels[0],
        preferredSpanishVoice: customConfig.preferredSpanishVoices[0],
        sttModelReady: customConfig.sttModel,
        cacheEntries: customCache.size(),
        promptVersion: customConfig.promptVersion
      });
      return;
    }

    if (method === "GET" && url === "/api/models") {
      if (!customConfig.hasGroqApiKey) {
        sendJson(res, 503, {
          error: "missing_api_key",
          message: "La variable GROQ_API_KEY no está configurada en el backend.",
          fallbackRequired: true
        });
        return;
      }
      try {
        const models = await customService.discoverModels();
        sendJson(res, 200, {
          status: "ok",
          models
        });
      } catch (err) {
        const safeMsg = sanitizeErrorMessage(
          err instanceof Error ? err.message : String(err),
          customConfig.getGroqApiKey()
        );
        sendJson(res, 502, {
          error: "groq_models_unavailable",
          message: safeMsg,
          fallbackRequired: true
        });
      }
      return;
    }

    if (method === "POST" && url === "/api/intervention") {
      if (!customConfig.hasGroqApiKey) {
        sendJson(res, 503, {
          error: "missing_api_key",
          message: "Falta configurar GROQ_API_KEY en backend/.env. Usando demo_locutor.wav.",
          fallbackRequired: true
        });
        return;
      }

      let body: unknown;
      try {
        body = await readJsonBody(req);
      } catch (err) {
        const code = err instanceof Error && err.message === "PAYLOAD_TOO_LARGE"
          ? "input_too_long"
          : "malformed_json";
        sendJson(res, 400, {
          error: code,
          message: "La solicitud enviada no es un JSON válido o supera el tamaño permitido.",
          fallbackRequired: true
        });
        return;
      }

      const validation = validateInterventionRequest(body);
      if (!validation.valid) {
        sendJson(res, 400, {
          error: validation.code,
          message: validation.error,
          fallbackRequired: true
        });
        return;
      }

      try {
        const result = await customService.generateIntervention(validation.data);
        sendJson(res, 200, { ...result });
      } catch (err) {
        const safeMsg = sanitizeErrorMessage(
          err instanceof Error ? err.message : String(err),
          customConfig.getGroqApiKey()
        );
        sendJson(res, 502, {
          error: "groq_generation_failed",
          message: safeMsg,
          fallbackRequired: true
        });
      }
      return;
    }

    // Endpoint directo para probar únicamente Text-to-Speech en español
    if (method === "POST" && url === "/api/tts") {
      if (!customConfig.hasGroqApiKey) {
        sendJson(res, 503, {
          error: "missing_api_key",
          message: "Falta configurar GROQ_API_KEY en backend/.env.",
          fallbackRequired: true
        });
        return;
      }

      try {
        const body = (await readJsonBody(req)) as Record<string, unknown>;
        const text = typeof body?.text === "string" ? body.text.trim() : "";
        if (!text || text.length > 420) {
          sendJson(res, 400, {
            error: "invalid_tts_input",
            message: "El texto para TTS debe tener entre 1 y 420 caracteres.",
            fallbackRequired: true
          });
          return;
        }

        const tts = await customService.generateSpanishSpeech(text, customConfig.getGroqApiKey());
        if (!tts.audioBase64) {
          sendJson(res, 422, {
            error: tts.errorCode || "spanish_voice_unavailable",
            message: tts.statusMessage,
            modelAttempted: tts.modelUsed,
            voiceAttempted: tts.voiceUsed,
            fallbackRequired: true
          });
          return;
        }

        sendJson(res, 200, {
          audioFormat: "wav",
          audioBase64: tts.audioBase64,
          modelUsed: tts.modelUsed,
          voiceUsed: tts.voiceUsed,
          statusMessage: tts.statusMessage
        });
      } catch (err) {
        const safeMsg = sanitizeErrorMessage(
          err instanceof Error ? err.message : String(err),
          customConfig.getGroqApiKey()
        );
        sendJson(res, 502, {
          error: "tts_failed",
          message: safeMsg,
          fallbackRequired: true
        });
      }
      return;
    }

    // Endpoint preparado para futura etapa de Speech-to-Text (mensajes de voz de oyentes)
    if (method === "POST" && url === "/api/stt") {
      if (!customConfig.hasGroqApiKey) {
        sendJson(res, 503, {
          error: "missing_api_key",
          message: "Falta configurar GROQ_API_KEY en backend/.env."
        });
        return;
      }

      try {
        const body = (await readJsonBody(req, 5 * 1024 * 1024)) as Record<string, unknown>;
        const audioBase64 = typeof body?.audioBase64 === "string" ? body.audioBase64 : "";
        const language = typeof body?.language === "string" ? body.language : "es";
        const result = await customService.transcribeListenerAudio({ audioBase64, language });
        sendJson(res, 200, { ...result });
      } catch (err) {
        const safeMsg = sanitizeErrorMessage(
          err instanceof Error ? err.message : String(err),
          customConfig.getGroqApiKey()
        );
        sendJson(res, 400, {
          error: "stt_failed",
          message: safeMsg
        });
      }
      return;
    }

    sendJson(res, 404, {
      error: "not_found",
      message: `Ruta no encontrada: ${method} ${url}`
    });
  });
}

if (require.main === module) {
  const server = createRadioMiaServer();
  server.listen(config.port, "0.0.0.0", () => {
    console.log(
      `[Radio Mía Backend] Escuchando en http://0.0.0.0:${config.port} (GROQ_API_KEY presente: ${config.hasGroqApiKey ? "SÍ" : "NO"})`
    );
  });
}

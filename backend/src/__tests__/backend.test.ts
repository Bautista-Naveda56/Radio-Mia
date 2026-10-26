import test from "node:test";
import assert from "node:assert/strict";
import {
  validateInterventionRequest,
  validateGeneratedScriptJson
} from "../types";
import { InterventionCache } from "../cache";
import { loadConfig, sanitizeErrorMessage } from "../config";
import { createRadioMiaServer } from "../index";

test("validateInterventionRequest rechaza entradas vacías o demasiado largas", () => {
  const missingTitle = validateInterventionRequest({ artist: "Soda Stereo" });
  assert.equal(missingTitle.valid, false);

  const tooLong = validateInterventionRequest({
    title: "A".repeat(300),
    artist: "Soda Stereo"
  });
  assert.equal(tooLong.valid, false);
  if (!tooLong.valid) {
    assert.equal(tooLong.code, "input_too_long");
  }

  const valid = validateInterventionRequest({
    title: "Persiana Americana",
    artist: "Soda Stereo",
    album: "Signos",
    genre: "Rock argentino",
    radioStyle: "Radio pop argentina",
    interventionType: "presentacion",
    maxDurationSeconds: 12
  });
  assert.equal(valid.valid, true);
});

test("validateGeneratedScriptJson valida la estructura devuelta por Groq", () => {
  const ok = validateGeneratedScriptJson(
    {
      type: "presentacion",
      text: "Seguimos con un clásico de Soda Stereo: Persiana Americana.",
      tone: "energico",
      estimatedDurationSeconds: 8
    },
    "presentacion",
    12
  );
  assert.equal(ok.valid, true);

  const empty = validateGeneratedScriptJson({ text: "" }, "presentacion", 12);
  assert.equal(empty.valid, false);
});

test("InterventionCache genera claves determinísticas sin guardar secretos", () => {
  const cache = new InterventionCache();
  const req = {
    title: "Persiana Americana",
    artist: "Soda Stereo",
    album: "Signos",
    genre: "Rock argentino",
    durationSeconds: 290,
    radioStyle: "Radio pop argentina",
    interventionType: "presentacion" as const,
    maxDurationSeconds: 12
  };
  const key1 = cache.buildKey(req, "v1.2");
  const key2 = cache.buildKey(req, "v1.2");
  assert.equal(key1, key2);
  assert.equal(key1.length, 24);
});

test("sanitizeErrorMessage oculta cualquier clave GROQ_API_KEY accidental", () => {
  const fakeSecret = "gsk_FAKE_SECRET_1234567890ABCDEFGHIJ";
  const dirty = `Error de red al autenticar con ${fakeSecret}`;
  const clean = sanitizeErrorMessage(dirty, fakeSecret);
  assert.equal(clean.includes(fakeSecret), false);
  assert.equal(clean.includes("[REDACTED]"), true);
});

test("Servidor responde 503 claro sin exponer secretos cuando falta GROQ_API_KEY", async () => {
  const emptyConfig = loadConfig({ PORT: "0", GROQ_API_KEY: "" });
  const server = createRadioMiaServer(emptyConfig);

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;

  try {
    const resp = await fetch(`http://127.0.0.1:${port}/api/intervention`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: "Persiana Americana",
        artist: "Soda Stereo"
      })
    });
    assert.equal(resp.status, 503);
    const data = (await resp.json()) as Record<string, unknown>;
    assert.equal(data.error, "missing_api_key");
    assert.equal(data.fallbackRequired, true);
  } finally {
    server.close();
  }
});

import crypto from "node:crypto";
import { InterventionRequest, InterventionResponse } from "./types";

export class InterventionCache {
  private readonly store = new Map<string, { value: InterventionResponse; expiresAt: number }>();
  private readonly maxEntries: number;
  private readonly ttlMs: number;

  constructor(maxEntries = 100, ttlMs = 1000 * 60 * 30) {
    this.maxEntries = maxEntries;
    this.ttlMs = ttlMs;
  }

  public buildKey(req: Required<InterventionRequest>, promptVersion: string): string {
    const raw = [
      req.title.trim().toLowerCase(),
      req.artist.trim().toLowerCase(),
      req.album.trim().toLowerCase(),
      req.radioStyle.trim().toLowerCase(),
      req.interventionType.trim().toLowerCase(),
      promptVersion.trim().toLowerCase()
    ].join("|");

    return crypto.createHash("sha256").update(raw, "utf8").digest("hex").slice(0, 24);
  }

  public get(key: string): InterventionResponse | null {
    const entry = this.store.get(key);
    if (!entry) return null;
    if (Date.now() > entry.expiresAt) {
      this.store.delete(key);
      return null;
    }
    return {
      ...entry.value,
      cached: true
    };
  }

  public set(key: string, value: InterventionResponse): void {
    if (this.store.size >= this.maxEntries) {
      const oldestKey = this.store.keys().next().value;
      if (oldestKey) {
        this.store.delete(oldestKey);
      }
    }
    this.store.set(key, {
      value: { ...value, cached: false },
      expiresAt: Date.now() + this.ttlMs
    });
  }

  public size(): number {
    return this.store.size;
  }

  public clear(): void {
    this.store.clear();
  }
}

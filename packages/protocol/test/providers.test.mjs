import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  CreateProviderRequestSchema,
  ProviderTypeSchema,
  TestConnectionResultSchema,
} from "../dist/index.js";

describe("provider protocol", () => {
  it("accepts openai_compatible with baseUrl + model", () => {
    const r = CreateProviderRequestSchema.safeParse({
      name: "My Provider",
      type: "openai_compatible",
      baseUrl: "https://example.com/v1",
      apiKey: "k",
      defaultModel: "m",
    });
    assert.equal(r.success, true);
  });

  it("rejects unknown provider type", () => {
    assert.equal(ProviderTypeSchema.safeParse("skynet").success, false);
  });

  it("test result schema carries latency + models", () => {
    const r = TestConnectionResultSchema.safeParse({
      status: "connected",
      latencyMs: 184,
      message: "Connected",
      models: ["a"],
      modelCount: 1,
    });
    assert.equal(r.success, true);
  });
});

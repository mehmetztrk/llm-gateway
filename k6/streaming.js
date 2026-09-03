// Streaming time-to-first-byte.
//
// k6 has no SSE client, so this measures `waiting` — the time until the response headers and the
// first body bytes arrive — which for a streamed response is exactly TTFB. The full body is then
// read, so the run also proves the stream terminates.
//
//   k6 run k6/streaming.js
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { provisionBenchTenant } from "./lib/provision.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const ttfb = new Trend("stream_ttfb", true);

const ADMIN_KEY = __ENV.ADMIN_KEY || "llmgw_local_admin_key_do_not_use_in_production";

export function setup() {
  return provisionBenchTenant(BASE_URL, ADMIN_KEY, "bench-stream");
}

export const options = {
  scenarios: { streaming: { executor: "constant-vus", vus: 20, duration: "30s" } },
  thresholds: {
    "stream_ttfb": ["p(99)<10"],
    http_req_failed: ["rate<0.01"],
  },
};

export default function (data) {
  const res = http.post(
    `${BASE_URL}/v1/chat/completions`,
    JSON.stringify({
      model: "mock-fast",
      stream: true,
      messages: [{ role: "user", content: `stream probe ${__VU}-${__ITER}` }],
    }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.apiKey}` } }
  );

  ttfb.add(res.timings.waiting);
  check(res, {
    "status is 200": (r) => r.status === 200,
    "stream terminated": (r) => r.body.includes("[DONE]"),
  });
}

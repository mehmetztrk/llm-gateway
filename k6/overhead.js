// Gateway overhead against MockProvider.
//
// WHY THE MOCK, NOT A REAL MODEL. A 1.5B model on a laptop GPU answers in tens to hundreds of
// milliseconds, with variance far larger than the thing being measured. Running this against
// Ollama would produce a number describing the GPU, not the gateway. MockProvider returns
// deterministic tokens with no I/O, so what is left in the measurement is the gateway's own work:
// auth, rate limiting, quota, cache lookup, routing, serialisation.
//
//   k6 run k6/overhead.js
//
// Environment:
//   BASE_URL  default http://localhost:8080
//   API_KEY   default the key seeded by the `local` profile
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { provisionBenchTenant } from "./lib/provision.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// Concurrency is a parameter because the interesting result is not one number but the level at
// which the target stops holding.
const PEAK_VUS = Number(__ENV.PEAK_VUS || 50);

const gatewayLatency = new Trend("gateway_latency", true);

const ADMIN_KEY = __ENV.ADMIN_KEY || "llmgw_local_admin_key_do_not_use_in_production";

export function setup() {
  return provisionBenchTenant(BASE_URL, ADMIN_KEY, "bench-overhead");
}

export const options = {
  scenarios: {
    // Ramp rather than a fixed rate: the interesting question is where latency starts to move,
    // and a single arrival rate cannot show that.
    overhead: {
      executor: "ramping-vus",
      stages: [
        { duration: "10s", target: Math.max(1, Math.floor(PEAK_VUS / 5)) },
        { duration: "20s", target: PEAK_VUS },
        { duration: "20s", target: PEAK_VUS },
        { duration: "5s", target: 0 },
      ],
      gracefulRampDown: "5s",
    },
  },
  thresholds: {
    // The milestone target. k6 fails the run if it is missed, so the number in BENCHMARKS.md
    // cannot quietly drift.
    "http_req_duration{expected_response:true}": ["p(99)<15"],
    http_req_failed: ["rate<0.01"],
  },
};

export default function (data) {
  // A unique prompt per iteration so the cache never hits. Measuring a cache hit would be
  // measuring the cache, which overhead.js is not about — cache.js does that on purpose.
  const body = JSON.stringify({
    model: "mock-fast",
    messages: [{ role: "user", content: `overhead probe ${__VU}-${__ITER}` }],
  });

  const res = http.post(`${BASE_URL}/v1/chat/completions`, body, {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.apiKey}` },
  });

  gatewayLatency.add(res.timings.duration);
  check(res, {
    "status is 200": (r) => r.status === 200,
    "body is a completion": (r) => r.json("object") === "chat.completion",
  });
}

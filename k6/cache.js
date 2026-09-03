// Cache effectiveness: hit ratio and the tokens it saves.
//
// A fixed pool of prompts, replayed. The pool size against the iteration count sets the
// theoretical ceiling on the hit ratio, so the measurement has something to be compared against
// rather than being a number with no scale.
//
//   k6 run k6/cache.js
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";
import { provisionBenchTenant } from "./lib/provision.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const POOL_SIZE = Number(__ENV.POOL_SIZE || 20);

const cacheHits = new Rate("cache_hit_rate");
const tokensSaved = new Counter("tokens_saved");

const ADMIN_KEY = __ENV.ADMIN_KEY || "llmgw_local_admin_key_do_not_use_in_production";

export function setup() {
  return provisionBenchTenant(BASE_URL, ADMIN_KEY, "bench-cache");
}

export const options = {
  scenarios: {
    replay: { executor: "constant-vus", vus: 10, duration: "30s" },
  },
  thresholds: {
    // With 10 VUs replaying 20 prompts for 30s, all but the first pass should hit. A floor well
    // below the theoretical ceiling, so the threshold fails on a broken cache rather than on
    // ordinary variance.
    cache_hit_rate: ["rate>0.80"],
    http_req_failed: ["rate<0.01"],
  },
};

// A run id keeps successive runs from inheriting the previous run's warm cache, which would
// report a hit ratio of ~1.0 and mean nothing.
const RUN_ID = `${Date.now()}`;

export default function (data) {
  const prompt = `cache probe ${RUN_ID} ${__ITER % POOL_SIZE}`;
  const res = http.post(
    `${BASE_URL}/v1/chat/completions`,
    JSON.stringify({ model: "mock-fast", messages: [{ role: "user", content: prompt }] }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.apiKey}` } }
  );

  const cache = res.headers["X-Llmgw-Cache"] || "miss";
  const hit = cache !== "miss";
  cacheHits.add(hit);
  if (hit) {
    tokensSaved.add(res.json("usage.total_tokens") || 0);
  }

  check(res, { "status is 200": (r) => r.status === 200 });
}

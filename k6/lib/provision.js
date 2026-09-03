// Provisions a tenant with limits high enough that the benchmark measures the gateway rather than
// its own rate limiter.
//
// The first attempt at these load tests failed 99.9% of requests, and that was the gateway working
// exactly as designed: the demo tenant allows 60 requests a minute and k6 was sending three
// thousand a second. Raising the limit for a benchmark tenant is not cheating — the limiter is
// still in the request path and its Redis round trips are still being measured. What changes is
// only that the bucket does not empty.
import http from "k6/http";

export function provisionBenchTenant(baseUrl, adminKey, prefix) {
  const headers = { "Content-Type": "application/json", Authorization: `Bearer ${adminKey}` };

  const tenant = http.post(
    `${baseUrl}/admin/tenants`,
    JSON.stringify({
      name: `${prefix}-${Date.now()}`,
      allowedModels: ["mock-fast", "mock-echo", "mock-ha"],
    }),
    { headers }
  );

  if (tenant.status !== 201) {
    throw new Error(`could not create the benchmark tenant: ${tenant.status} ${tenant.body}`);
  }
  const tenantId = tenant.json("id");

  const limits = http.put(
    `${baseUrl}/admin/tenants/${tenantId}/limits`,
    JSON.stringify({
      requestsPerMinute: 100000000,
      tokensPerMinute: 100000000000,
      monthlyTokenBudget: null,
    }),
    { headers }
  );
  if (limits.status !== 200) {
    throw new Error(`could not raise limits: ${limits.status} ${limits.body}`);
  }

  const key = http.post(
    `${baseUrl}/admin/tenants/${tenantId}/keys`,
    JSON.stringify({ role: "TENANT", label: "k6" }),
    { headers }
  );
  if (key.status !== 201) {
    throw new Error(`could not issue a key: ${key.status} ${key.body}`);
  }

  return { tenantId, apiKey: key.json("key") };
}

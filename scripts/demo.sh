#!/usr/bin/env bash
#
# A guided tour of the gateway, for a human who is watching.
#
#   ./scripts/demo.sh
#
# Unlike the integration tests, this one really does kill a container. A test cannot afford that —
# it would depend on Docker lifecycle timing and on a cleanup path that must run even when an
# assertion fails — but a demo can, and watching a provider die is more convincing than reading
# that it was configured to fail.
#
# Assumes: docker compose up -d, and the gateway running with the `local` profile.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_KEY="${ADMIN_KEY:-llmgw_local_admin_key_do_not_use_in_production}"
TENANT_KEY="${TENANT_KEY:-llmgw_local_demo_key_do_not_use_in_production}"

readonly BOLD=$'\033[1m' DIM=$'\033[2m' GREEN=$'\033[32m' YELLOW=$'\033[33m' RESET=$'\033[0m'
step()  { printf '\n%s━━ %s%s\n' "$BOLD" "$1" "$RESET"; }
note()  { printf '%s   %s%s\n' "$DIM" "$1" "$RESET"; }
ok()    { printf '%s   ✓ %s%s\n' "$GREEN" "$1" "$RESET"; }
pause() { printf '%s   (enter to continue)%s' "$DIM" "$RESET"; read -r; }

tenant() { curl -sS -H "Authorization: Bearer $TENANT_KEY" "$@"; }
admin()  { curl -sS -H "Authorization: Bearer $ADMIN_KEY" "$@"; }

complete() {
  tenant -X POST "$BASE_URL/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"$1\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}"
}

curl -fsS --max-time 3 "$BASE_URL/actuator/health" >/dev/null 2>&1 || {
  echo "The gateway is not answering on $BASE_URL."
  echo "Start it with: ./gradlew bootRun --args='--spring.profiles.active=local'"
  exit 1
}

step "1. It is an OpenAI-compatible endpoint"
note "Any OpenAI SDK works by changing base_url and nothing else."
complete "mock-fast" "hello from the demo" | head -c 400; echo
pause

step "2. Authentication is required, and says so in OpenAI's error shape"
note "No key:"
curl -sS -X POST "$BASE_URL/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}' | head -c 300; echo
pause

step "3. Caching — the same prompt twice"
PROMPT="cache demo $(date +%s)"
note "First call:"
complete "mock-fast" "$PROMPT" >/dev/null
tenant -o /dev/null -D - -X POST "$BASE_URL/v1/chat/completions" -H 'Content-Type: application/json' \
  -d "{\"model\":\"mock-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"$PROMPT\"}]}" \
  2>/dev/null | grep -i "x-llmgw-cache" || true
ok "second call served from cache, with no tokens spent upstream"
pause

step "4. Rate limits are reported on every response, not only on 429"
tenant -o /dev/null -D - -X POST "$BASE_URL/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"mock-fast","messages":[{"role":"user","content":"limits"}]}' \
  2>/dev/null | grep -i "x-ratelimit" || true
pause

step "5. Provider health, as routing currently believes it"
admin "$BASE_URL/admin/providers" | head -c 500; echo
pause

step "6. Chaos: killing the primary Ollama container"
note "The chat-default alias routes to ollama-primary, then ollama-secondary."
if docker ps --format '{{.Names}}' | grep -q '^llmgw-ollama-primary$'; then
  docker stop llmgw-ollama-primary >/dev/null
  ok "llmgw-ollama-primary stopped"
  note "Waiting for the health probe to notice…"
  sleep 5
  admin "$BASE_URL/admin/providers" | head -c 500; echo
  note "The alias still answers:"
  time complete "chat-default" "are you still there" | head -c 300; echo
  note "Restarting the primary…"
  docker start llmgw-ollama-primary >/dev/null
  ok "llmgw-ollama-primary restarted; the probe will mark it UP again"
else
  printf '%s   (ollama-primary is not running; skipping)%s\n' "$YELLOW" "$RESET"
fi
pause

step "7. What every request cost"
tenant "$BASE_URL/v1/usage" | head -c 600; echo
note "Tokens are measured. Cost is arithmetic over a reference price table — every model here is free."

step "Done"
note "Console:    $BASE_URL/"
note "Metrics:    $BASE_URL/actuator/prometheus"
note "Dashboards: docker compose --profile observability up -d, then http://localhost:3000"

#!/usr/bin/env bash
#
# Downloads the models each Ollama instance serves. Run once, after `docker compose up -d`.
#
# The split is deliberate: the primary carries the chat model plus the embedding model used by
# the semantic cache (M6); the secondary carries only a smaller chat model, because it runs on
# CPU and exists to prove failover, not to be fast.

set -euo pipefail

readonly BOLD=$'\033[1m' GREEN=$'\033[32m' RESET=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$BOLD" "$1" "$RESET"; }
ok()   { printf '%s  ok%s %s\n' "$GREEN" "$RESET" "$1"; }

PRIMARY_MODELS=("qwen2.5:1.5b-instruct" "nomic-embed-text")
SECONDARY_MODELS=("llama3.2:1b")

wait_for() {
    local service="$1"
    printf '    waiting for %s ' "$service"
    for _ in $(seq 1 60); do
        if docker compose exec -T "$service" ollama list >/dev/null 2>&1; then
            printf 'ready\n'
            return 0
        fi
        printf '.'
        sleep 2
    done
    printf '\n'
    echo "ERROR: $service did not become ready. Check: docker compose logs $service" >&2
    exit 1
}

step "ollama-primary (GPU)"
wait_for ollama-primary
for model in "${PRIMARY_MODELS[@]}"; do
    docker compose exec -T ollama-primary ollama pull "$model"
    ok "$model"
done

step "ollama-secondary (CPU, failover target)"
wait_for ollama-secondary
for model in "${SECONDARY_MODELS[@]}"; do
    docker compose exec -T ollama-secondary ollama pull "$model"
    ok "$model"
done

step "Installed models"
echo "primary:"
docker compose exec -T ollama-primary ollama list
echo "secondary:"
docker compose exec -T ollama-secondary ollama list

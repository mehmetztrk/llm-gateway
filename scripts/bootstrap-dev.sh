#!/usr/bin/env bash
#
# One-shot host setup for the LLM gateway on Debian/Ubuntu.
#
#   ./scripts/bootstrap-dev.sh
#
# Installs, skipping anything already present:
#   1. Temurin JDK 21 via SDKMAN            (user-level, no sudo)
#   2. Docker Engine + compose plugin       (sudo)
#   3. NVIDIA Container Toolkit             (sudo, only if an NVIDIA GPU is detected)
#   4. k6 load-testing CLI                  (sudo, needed from M9 onwards)
#
# Ollama is deliberately NOT installed on the host: both instances run as containers so that
# `docker compose up -d` reproduces the whole system on any machine.
#
# The script is idempotent — re-running it is safe.

set -euo pipefail

JAVA_VERSION="21.0.12+1.1-tem"

readonly BOLD=$'\033[1m' GREEN=$'\033[32m' YELLOW=$'\033[33m' RED=$'\033[31m' RESET=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$BOLD" "$1" "$RESET"; }
ok()   { printf '%s  ok%s %s\n' "$GREEN" "$RESET" "$1"; }
warn() { printf '%s  !!%s %s\n' "$YELLOW" "$RESET" "$1"; }
die()  { printf '%s  xx%s %s\n' "$RED" "$RESET" "$1" >&2; exit 1; }

[[ "$(uname -s)" == "Linux" ]] || die "This script targets Linux. On macOS/Windows install Docker Desktop and SDKMAN manually."
command -v apt-get >/dev/null || die "No apt-get found. This script targets Debian/Ubuntu."

if [[ $EUID -eq 0 ]]; then
    die "Run as your normal user, not root. The script calls sudo only where it must."
fi

step "Checking sudo access (needed for Docker and k6)"
sudo -v || die "sudo is required."
ok "sudo granted"

# ---------------------------------------------------------------- 1. Java 21
step "Java 21 (Temurin, via SDKMAN)"
export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"

if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
    curl -s "https://get.sdkman.io?rcupdate=false" | bash
    ok "SDKMAN installed to $SDKMAN_DIR"
else
    ok "SDKMAN already present"
fi

# shellcheck disable=SC1091
source "$SDKMAN_DIR/bin/sdkman-init.sh"

if [[ -d "$SDKMAN_DIR/candidates/java/$JAVA_VERSION" ]]; then
    ok "JDK $JAVA_VERSION already installed"
else
    sdk install java "$JAVA_VERSION" < /dev/null
    ok "JDK $JAVA_VERSION installed"
fi

# Make sdk available in future interactive shells.
if ! grep -q 'sdkman-init.sh' "$HOME/.bashrc" 2>/dev/null; then
    {
        echo ''
        echo '# SDKMAN (added by llm-gateway bootstrap-dev.sh)'
        echo 'export SDKMAN_DIR="$HOME/.sdkman"'
        echo '[[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"'
    } >> "$HOME/.bashrc"
    ok "SDKMAN init appended to ~/.bashrc"
else
    ok "~/.bashrc already sources SDKMAN"
fi

# ---------------------------------------------------------------- 2. Docker
step "Docker Engine + compose plugin"
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
    ok "Docker and the compose plugin are already installed"
else
    sudo apt-get update -qq
    sudo apt-get install -y -qq ca-certificates curl gnupg

    sudo install -m 0755 -d /etc/apt/keyrings
    if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
        sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
        sudo chmod a+r /etc/apt/keyrings/docker.asc
    fi

    # shellcheck disable=SC1091
    codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \
        | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    sudo apt-get update -qq
    sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    ok "Docker installed"
fi

if getent group docker | grep -qw "$USER"; then
    ok "$USER is already in the docker group"
else
    sudo usermod -aG docker "$USER"
    warn "Added $USER to the docker group — LOG OUT AND BACK IN (or run 'newgrp docker') before using docker without sudo."
fi

sudo systemctl enable --now docker >/dev/null 2>&1 || warn "Could not enable the docker service; start it manually if needed."

# ---------------------------------------------------------- 3. NVIDIA toolkit
step "NVIDIA Container Toolkit (GPU passthrough for Ollama)"
if ! command -v nvidia-smi >/dev/null; then
    warn "No nvidia-smi found — skipping. Set COMPOSE_FILE=docker/compose.yaml in .env to run CPU-only."
elif command -v nvidia-ctk >/dev/null; then
    ok "nvidia-container-toolkit already installed"
else
    if [[ ! -f /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg ]]; then
        curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
            | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
    fi
    curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
        | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
        | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list > /dev/null

    sudo apt-get update -qq
    sudo apt-get install -y -qq nvidia-container-toolkit
    sudo nvidia-ctk runtime configure --runtime=docker
    sudo systemctl restart docker
    ok "nvidia-container-toolkit installed and wired into Docker"
fi

# ---------------------------------------------------------------- 4. k6
step "k6 (load testing, used from M9)"
if command -v k6 >/dev/null; then
    ok "k6 already installed"
else
    if [[ ! -f /usr/share/keyrings/k6-archive-keyring.gpg ]]; then
        curl -fsSL https://dl.k6.io/key.gpg \
            | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
    fi
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
        | sudo tee /etc/apt/sources.list.d/k6.list > /dev/null
    sudo apt-get update -qq
    sudo apt-get install -y -qq k6
    ok "k6 installed"
fi

# ---------------------------------------------------------------- summary
step "Summary"
"$SDKMAN_DIR/candidates/java/$JAVA_VERSION/bin/java" -version 2>&1 | head -1
docker --version 2>/dev/null || warn "docker not on PATH yet"
docker compose version 2>/dev/null | head -1 || true
k6 version 2>/dev/null || true

cat <<'NEXT'

Next steps:
  1. If the script added you to the docker group, log out and back in (or: newgrp docker).
  2. docker compose up -d          # from the repository root
  3. ./scripts/pull-models.sh      # downloads the Ollama models (~2 GB, one time)
  4. ./gradlew check               # build and test

NEXT

#!/usr/bin/env bash
#
# One-shot host setup for the LLM gateway on Debian/Ubuntu.
#
#   ./scripts/bootstrap-dev.sh              # install everything, then start the stack
#   ./scripts/bootstrap-dev.sh --no-stack   # install only, do not start containers
#
# Installs, skipping anything already present:
#   1. Temurin JDK 21 via SDKMAN            (user-level, no sudo)
#   2. Docker Engine + compose plugin       (sudo)
#   3. NVIDIA Container Toolkit             (sudo, only if an NVIDIA GPU is detected)
#   4. k6 load-testing CLI                  (sudo, needed from M9 onwards)
#   5. Brings up the compose stack and downloads the Ollama models
#
# You are prompted for your sudo password exactly once, at the start. A background keepalive
# refreshes the credential so a long apt run cannot trigger a second prompt halfway through.
#
# Ollama is deliberately NOT installed on the host: both instances run as containers so that
# `docker compose up -d` reproduces the whole system on any machine.
#
# The script is idempotent — re-running it is safe.

set -euo pipefail

JAVA_VERSION="21.0.12+1.1-tem"
START_STACK=1
[[ "${1:-}" == "--no-stack" ]] && START_STACK=0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
# why not a bare `sudo -v`: sudo's default verifypw=all makes -v prompt for a password if *any*
# sudoers entry matching the user requires one — which is true for every member of the `sudo`
# group, even when a NOPASSWD rule is what actually applies. That turns -v into a hard failure
# under any non-interactive run. Probe with -n first and only fall back to prompting.
if sudo -n true 2>/dev/null; then
    ok "passwordless sudo available"
else
    sudo -v || die "sudo is required."
fi

# Keep the sudo timestamp warm for the rest of the run. Installing Docker takes longer than the
# default 15-minute timestamp on a slow connection, and a second password prompt buried in apt
# output is exactly the kind of thing that leaves a half-installed machine behind.
while true; do
    sudo -n true 2>/dev/null || exit
    sleep 60
done &
SUDO_KEEPALIVE_PID=$!
# shellcheck disable=SC2064
trap "kill $SUDO_KEEPALIVE_PID 2>/dev/null || true" EXIT
ok "sudo granted (you will not be asked again)"

# ---------------------------------------------------------------- 1. Java 21
step "Java 21 (Temurin, via SDKMAN)"
export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"

if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
    curl -s "https://get.sdkman.io?rcupdate=false" | bash
    ok "SDKMAN installed to $SDKMAN_DIR"
else
    ok "SDKMAN already present"
fi

# sdkman-init.sh reads variables it has not always set, so it aborts under `set -u`. Relaxing
# nounset only around the source keeps the rest of this script strict.
set +u
# shellcheck disable=SC1091
source "$SDKMAN_DIR/bin/sdkman-init.sh"
set -u

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

# ------------------------------------------------------- 5. start the stack
# why `sg docker -c`: `usermod -aG` only affects newly created login sessions, so the shell
# running this script still lacks the docker group. `sg` starts a subshell with the group
# applied, which avoids forcing a logout in the middle of setup.
dockerx() { sg docker -c "cd '$REPO_ROOT' && $1"; }

if [[ $START_STACK -eq 1 ]]; then
    step "Verifying GPU passthrough"
    if ! command -v nvidia-smi >/dev/null; then
        gpu_ok=0
    elif dockerx "docker run --rm --gpus all ubuntu:22.04 nvidia-smi -L" >/dev/null 2>&1; then
        gpu_ok=1
        ok "containers can see the GPU"
    else
        gpu_ok=0
    fi

    if [[ $gpu_ok -eq 0 ]]; then
        warn "GPU not usable from containers — falling back to CPU-only."
        # Rewrite COMPOSE_FILE rather than failing: a CPU-only stack is slower but complete,
        # and MockProvider (what the benchmarks actually measure) never touches the GPU.
        sed -i 's|^COMPOSE_FILE=.*|COMPOSE_FILE=docker/compose.yaml|' "$REPO_ROOT/.env"
        ok ".env switched to docker/compose.yaml (CPU-only)"
    fi

    step "Starting the stack"
    dockerx "docker compose up -d"
    dockerx "docker compose ps"

    step "Downloading Ollama models (~2 GB, one time)"
    dockerx "./scripts/pull-models.sh"
fi

# ---------------------------------------------------------------- summary
step "Summary"
"$SDKMAN_DIR/candidates/java/$JAVA_VERSION/bin/java" -version 2>&1 | head -1
sg docker -c "docker --version" 2>/dev/null || warn "docker not usable yet"
sg docker -c "docker compose version" 2>/dev/null | head -1 || true
k6 version 2>/dev/null | head -1 || true

cat <<'NEXT'

Done. Log out and back in once so `docker` works without `sg docker -c` in your own shell.

Then:
  ./gradlew check                                            # build and test
  ./gradlew bootRun --args='--spring.profiles.active=local'  # run the gateway
  curl -s localhost:8080/actuator/health

NEXT

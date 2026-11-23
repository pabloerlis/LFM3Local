#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="4663f9ca8a97c525ba3d248711fa2b52050920ec"
mkdir -p "$REPO_ROOT/vendor"
if [ ! -d "$REPO_ROOT/vendor/llama.cpp/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp.git "$REPO_ROOT/vendor/llama.cpp"
fi
git -C "$REPO_ROOT/vendor/llama.cpp" fetch origin "$PIN"
git -C "$REPO_ROOT/vendor/llama.cpp" checkout --detach "$PIN"
git -C "$REPO_ROOT/vendor/llama.cpp" submodule update --init --recursive
echo "llama.cpp pinned: $PIN"

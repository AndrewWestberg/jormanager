#!/bin/bash
set -euo pipefail

pushd "$(dirname "$0")" >/dev/null
trap 'popd >/dev/null' EXIT

NODE_MAJOR=0
if command -v node >/dev/null 2>&1; then
  NODE_MAJOR=$(node -p "process.versions.node.split('.')[0]" 2>/dev/null || echo 0)
fi

if [ "$NODE_MAJOR" -lt 26 ]; then
  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [ -s "$NVM_DIR/nvm.sh" ]; then
    # nvm.sh is not nounset-safe
    set +u
    # shellcheck disable=SC1091
    source "$NVM_DIR/nvm.sh"
    nvm use 26
    set -u
  else
    echo "Error: Node.js >= 26 is required (found v${NODE_MAJOR}) and nvm was not found at ${NVM_DIR}" >&2
    exit 1
  fi
fi

if [ ! -d node_modules ]; then
  npm ci
fi

npm run build

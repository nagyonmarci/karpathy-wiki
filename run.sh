#!/bin/sh
# Elindítja a karpathy-wiki-t. Ha az Ollama már fut a hoston, azt használja;
# ha nem, elindítja az Ollama Docker containert is.
# Nem interaktív parancsoknál (compile, import, lint, stb.) a wiki containert
# újraindítja, hogy MkDocs friss tartalmat tálaljon.

if curl -sf http://localhost:11434 > /dev/null 2>&1; then
  echo "Ollama already running on host — using host.docker.internal:11434"
  docker compose run --rm karpathy-wiki "$@"
else
  echo "Ollama not detected — starting Ollama container"
  OLLAMA_BASE_URL=http://ollama:11434 docker compose --profile ollama run --rm karpathy-wiki "$@"
fi

# Ha adtunk parancsot (nem interaktív), frissítjük a wiki nézetet
if [ $# -gt 0 ]; then
  docker compose up -d --force-recreate wiki 2>/dev/null || true
fi

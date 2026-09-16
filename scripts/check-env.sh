#!/usr/bin/env sh
# Checks .env before you start the monitor, and can send a real Telegram test message.
#
#   sh scripts/check-env.sh              # only inspects the file
#   sh scripts/check-env.sh --telegram   # also sends a test message via the Bot API
#
# Secrets are never printed: the script reports presence and shape, never values.

set -eu

ENV_FILE="${ENV_FILE:-.env}"
FAIL=0

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
warn()  { printf '\033[33m%s\033[0m\n' "$1"; }

if [ ! -f "$ENV_FILE" ]; then
  red "FALTA  $ENV_FILE nao existe. Rode: cp .env.example .env"
  exit 1
fi

# Reads a value without sourcing the file (so a stray line cannot execute anything).
value_of() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- || true
}

require() {
  name="$1"; why="$2"
  v="$(value_of "$name")"
  if [ -z "$v" ]; then
    red "FALTA  $name — $why"
    FAIL=1
  else
    green "OK     $name (${#v} caracteres)"
  fi
}

optional() {
  name="$1"; why="$2"
  v="$(value_of "$name")"
  if [ -z "$v" ]; then
    warn "VAZIO  $name — $why"
  else
    green "OK     $name (${#v} caracteres)"
  fi
}

echo "=== Obrigatorio ==="
require DATABASE_PASSWORD "senha do Postgres; o docker compose se recusa a subir sem ela"

echo
echo "=== Telegram ==="
if [ "$(value_of TELEGRAM_ENABLED)" = "false" ]; then
  warn "DESLIGADO  TELEGRAM_ENABLED=false — precos serao coletados, mas sem avisos"
else
  require TELEGRAM_BOT_TOKEN "token do @BotFather"
  require TELEGRAM_CHAT_ID "id do chat que deve receber os alertas"

  token="$(value_of TELEGRAM_BOT_TOKEN)"
  if [ -n "$token" ] && ! printf '%s' "$token" | grep -qE '^[0-9]+:[A-Za-z0-9_-]+$'; then
    red "AVISO  TELEGRAM_BOT_TOKEN nao tem o formato <numeros>:<letras>. Copiou inteiro?"
    FAIL=1
  fi
fi

echo
echo "=== Providers (pelo menos um precisa estar utilizavel) ==="
usable=0
[ "$(value_of SERPAPI_ENABLED)" != "false" ] && [ -n "$(value_of SERPAPI_KEY)" ] && usable=$((usable + 1))
[ "$(value_of TRAVELPAYOUTS_ENABLED)" != "false" ] && [ -n "$(value_of TRAVELPAYOUTS_TOKEN)" ] && usable=$((usable + 1))
[ "$(value_of FIXTURE_ENABLED)" = "true" ] && usable=$((usable + 1))

optional SERPAPI_KEY "sem ela nao ha itinerarios com escalas nomeadas (o filtro de paises perde forca)"
optional TRAVELPAYOUTS_TOKEN "sem ele o historico fica so com o que o SerpApi couber no orcamento"

if [ "$(value_of FIXTURE_ENABLED)" = "true" ]; then
  warn "ATENCAO  FIXTURE_ENABLED=true — DADOS SIMULADOS. Nenhum preco e real."
fi

if [ "$usable" -eq 0 ]; then
  red "FALTA  nenhum provider utilizavel. O /actuator/health vai reportar DOWN."
  FAIL=1
else
  green "OK     $usable provider(s) utilizavel(is)"
fi

echo
echo "=== Viagem ==="
echo "As viagens ficam em src/main/resources/application.yml:"
echo "  tokyo-rio-poa-2027   09/01 HND/NRT -> GIG/SDU + 19/01 POA -> HND/NRT, ate GBP 2000"
echo "  rio-poa-2027-01-13   13/01 GIG/SDU -> POA, so ida, ate R\$ 350"
echo "  poa-rio-2027         11/01 POA -> GIG/SDU, volta 13/01, ate R\$ 800"
for key in TOKYO_MAX_PRICE_GBP TOKYO_DOMESTIC_MAX_PRICE RIO_TARGET_MAX_PRICE; do
  v="$(value_of "$key")"
  if [ -n "$v" ]; then printf 'override    %s=%s\n' "$key" "$v"; fi
done
printf 'bloqueados  %s\n'  "$(value_of BLOCKED_COUNTRIES)"

echo
if [ "$FAIL" -eq 0 ]; then
  green "Tudo pronto. Suba com: docker compose up -d"
else
  red "Ha itens faltando acima."
fi

# ------------------------------------------------------------------------------------------------
# Optional live check. Talks to the Bot API directly, so it works before the app is even built.
# ------------------------------------------------------------------------------------------------
if [ "${1:-}" = "--telegram" ]; then
  echo
  echo "=== Teste real no Telegram ==="
  token="$(value_of TELEGRAM_BOT_TOKEN)"
  chat="$(value_of TELEGRAM_CHAT_ID)"
  if [ -z "$token" ] || [ -z "$chat" ]; then
    red "Sem token/chat id para testar."
    exit 1
  fi
  response="$(curl -s -X POST "https://api.telegram.org/bot$token/sendMessage" \
    -d "chat_id=$chat" \
    --data-urlencode "text=Flight Monitor: canal configurado com sucesso.")"
  if printf '%s' "$response" | grep -q '"ok":true'; then
    green "Mensagem entregue. Confira o Telegram."
  else
    red "Falhou. Resposta da API:"
    # Shows only the description field, so the URL with the token never appears.
    printf '%s\n' "$response" | sed 's/.*"description":"\([^"]*\)".*/  \1/'
    exit 1
  fi
fi

exit "$FAIL"

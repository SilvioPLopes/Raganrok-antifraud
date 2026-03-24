#!/bin/bash
# =============================================================================
# ragnarok-antifraude — STRESS TEST MASSIVO
# =============================================================================
# Testa os limites do microsserviço antifraude sob alta concorrência.
#
# Pré-requisitos:
#   - curl, jq, bc instalados
#   - ragnarok-antifraude rodando em localhost:8081
#   - GNU parallel (opcional, para máxima concorrência)
#
# Uso:
#   chmod +x stress_test.sh
#   ./stress_test.sh [TOTAL_REQUESTS] [CONCURRENCY]
#
# Exemplos:
#   ./stress_test.sh 1000 50    # 1000 requisições, 50 concorrentes (padrão)
#   ./stress_test.sh 5000 200   # 5000 requisições, 200 concorrentes
#   ./stress_test.sh 10000 500  # stress extremo — detecta gargalos de thread pool
# =============================================================================

set -euo pipefail

HOST="${FRAUD_HOST:-http://localhost:8081}"
ENDPOINT="$HOST/api/fraud/analyze"
TOTAL="${1:-1000}"
CONCURRENCY="${2:-50}"
RESULTS_DIR="./stress-results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_FILE="$RESULTS_DIR/stress_$TIMESTAMP.tsv"

mkdir -p "$RESULTS_DIR"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   RAGNAROK ANTI-FRAUDE — STRESS TEST MASSIVO         ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  Host:        ${YELLOW}$HOST${NC}"
echo -e "  Total:       ${YELLOW}$TOTAL requests${NC}"
echo -e "  Concorrência: ${YELLOW}$CONCURRENCY simultâneos${NC}"
echo -e "  Resultados:  ${YELLOW}$RESULTS_FILE${NC}"
echo ""

# ── Health check ─────────────────────────────────────────────────────────────
echo -e "${CYAN}[1/5] Verificando health do serviço...${NC}"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/api/fraud/health")
if [ "$HTTP_STATUS" != "200" ]; then
    echo -e "${RED}ERRO: serviço não está UP (HTTP $HTTP_STATUS). Abortando.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Serviço respondendo (HTTP 200)${NC}"
echo ""

# ── Funções de geração de payload ────────────────────────────────────────────
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c "import uuid; print(uuid.uuid4())"; }

payload_item_trade() {
    local player_id=$((RANDOM % 10000 + 1))
    local item_uuid=$(uuid)
    local zeny_value=$((RANDOM % 1000000 + 100))
    cat <<JSON
{
  "eventId":   "$(uuid)",
  "eventType": "ITEM_TRADE",
  "playerId":  $player_id,
  "ipAddress": "200.$((RANDOM%255)).$((RANDOM%255)).$((RANDOM%255))",
  "countryCode": "BR",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": {
    "itemId":    $((RANDOM % 1000 + 1)),
    "itemUuid":  "$item_uuid",
    "zenysValue": $zeny_value
  }
}
JSON
}

payload_dupe_attack() {
    # Simula dupe: mesmo itemUuid, dois eventos simultâneos
    cat <<JSON
{
  "eventId":   "$(uuid)",
  "eventType": "ITEM_TRADE",
  "playerId":  666,
  "ipAddress": "10.0.0.1",
  "countryCode": "BR",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": {
    "itemId":    9999,
    "itemUuid":  "DUPE-ITEM-UUID-FIXED-001",
    "zenysValue": 500
  }
}
JSON
}

payload_bot_click() {
    cat <<JSON
{
  "eventId":   "$(uuid)",
  "eventType": "CLICK_ACTION",
  "playerId":  $((RANDOM % 10000 + 1)),
  "ipAddress": "192.168.$((RANDOM%255)).$((RANDOM%255))",
  "countryCode": "BR",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": {
    "actionsPerSecond": $(echo "scale=1; $((RANDOM % 300)) / 10" | bc),
    "networkLatencyMs": $(echo "scale=1; $((RANDOM % 500)) / 10" | bc)
  }
}
JSON
}

payload_impossible_travel() {
    local countries=("BR" "US" "KR" "JP" "DE" "FR" "CN" "RU" "IN" "AU")
    local country=${countries[$RANDOM % ${#countries[@]}]}
    cat <<JSON
{
  "eventId":   "$(uuid)",
  "eventType": "SESSION_LOGIN",
  "playerId":  $((RANDOM % 100 + 1)),
  "ipAddress": "$((RANDOM%255)).$((RANDOM%255)).$((RANDOM%255)).$((RANDOM%255))",
  "countryCode": "$country",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": {
    "emailVerified": true,
    "ageVerified":   true
  }
}
JSON
}

payload_zeny_bomb() {
    # Simula transferência absurda: 1B zenys por item de 100z
    cat <<JSON
{
  "eventId":   "$(uuid)",
  "eventType": "ITEM_TRADE",
  "playerId":  $((RANDOM % 10000 + 1)),
  "ipAddress": "10.0.0.$((RANDOM%255))",
  "countryCode": "BR",
  "occurredAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "payload": {
    "itemId":    607,
    "itemUuid":  "$(uuid)",
    "zenysValue": 1000000000
  }
}
JSON
}

# ── Cenários de teste ─────────────────────────────────────────────────────────
# Distribuição de carga simulando tráfego real de um MMO:
#   60% → trocas normais (ITEM_TRADE clean)
#   15% → clicks de combate (CLICK_ACTION)
#   10% → logins (SESSION_LOGIN)
#   10% → ataques de dupe
#    5% → zeny bombs

echo -e "${CYAN}[2/5] Gerando $TOTAL payloads de teste...${NC}"
PAYLOAD_DIR=$(mktemp -d)

for i in $(seq 1 $TOTAL); do
    RAND=$((RANDOM % 100))
    if   [ $RAND -lt 60 ]; then payload_item_trade    > "$PAYLOAD_DIR/req_$i.json"
    elif [ $RAND -lt 75 ]; then payload_bot_click      > "$PAYLOAD_DIR/req_$i.json"
    elif [ $RAND -lt 85 ]; then payload_impossible_travel > "$PAYLOAD_DIR/req_$i.json"
    elif [ $RAND -lt 95 ]; then payload_dupe_attack    > "$PAYLOAD_DIR/req_$i.json"
    else                        payload_zeny_bomb       > "$PAYLOAD_DIR/req_$i.json"
    fi
done
echo -e "${GREEN}✓ Payloads gerados${NC}"
echo ""

# ── Execução do stress test ───────────────────────────────────────────────────
echo -e "${CYAN}[3/5] Executando stress test ($CONCURRENCY simultâneos)...${NC}"
echo -e "─────────────────────────────────────────────────────"

# Header do arquivo de resultados
echo -e "seq\thttp_status\tlatency_ms\tverdict\trisk_level\ttriggered_rules" > "$RESULTS_FILE"

SUCCESS=0
FAIL=0
TIMEOUT_COUNT=0
TOTAL_LATENCY=0
MAX_LATENCY=0
MIN_LATENCY=999999

run_request() {
    local seq=$1
    local payload_file=$2

    local start_ms=$(($(date +%s%N) / 1000000))

    local response
    response=$(curl -s -w "\n%{http_code}" \
        --max-time 5 \
        -X POST "$ENDPOINT" \
        -H "Content-Type: application/json" \
        -d @"$payload_file" 2>/dev/null) || echo -e "\n000"

    local end_ms=$(($(date +%s%N) / 1000000))
    local latency_ms=$((end_ms - start_ms))

    local http_status=$(echo "$response" | tail -1)
    local body=$(echo "$response" | head -n -1)

    local verdict="N/A"
    local risk="N/A"
    local rules="N/A"

    if [ "$http_status" = "200" ] && command -v jq &>/dev/null; then
        verdict=$(echo "$body" | jq -r '.verdict // "N/A"' 2>/dev/null || echo "N/A")
        risk=$(echo "$body"    | jq -r '.riskLevel // "N/A"' 2>/dev/null || echo "N/A")
        rules=$(echo "$body"   | jq -r '(.triggeredRules // []) | join(",")' 2>/dev/null || echo "")
    fi

    echo -e "$seq\t$http_status\t$latency_ms\t$verdict\t$risk\t$rules"
}

export -f run_request
export ENDPOINT

# Execução com GNU parallel se disponível, senão xargs
if command -v parallel &>/dev/null; then
    seq 1 $TOTAL | parallel -j "$CONCURRENCY" run_request {} "$PAYLOAD_DIR/req_{}.json" >> "$RESULTS_FILE"
else
    # Fallback: xargs com jobs paralelos
    for i in $(seq 1 $TOTAL); do echo "$i $PAYLOAD_DIR/req_$i.json"; done | \
    xargs -P "$CONCURRENCY" -n 2 bash -c 'run_request "$@"' _ >> "$RESULTS_FILE"
fi

echo ""
echo -e "${GREEN}✓ Stress test concluído${NC}"
echo ""

# ── Análise dos resultados ────────────────────────────────────────────────────
echo -e "${CYAN}[4/5] Analisando resultados...${NC}"
echo -e "─────────────────────────────────────────────────────"

# Conta status HTTP (pula header)
SUCCESS=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$2=="200"{c++}END{print c+0}')
FAIL=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$2!="200"&&$2!="000"{c++}END{print c+0}')
TIMEOUT_COUNT=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$2=="000"{c++}END{print c+0}')

# Latências
LATENCIES=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$2=="200"{print $3}')
if [ -n "$LATENCIES" ]; then
    AVG_LATENCY=$(echo "$LATENCIES" | awk '{sum+=$1;n++}END{printf "%.1f", sum/n}')
    MAX_LATENCY=$(echo "$LATENCIES" | sort -n | tail -1)
    MIN_LATENCY=$(echo "$LATENCIES" | sort -n | head -1)
    P95_LATENCY=$(echo "$LATENCIES" | sort -n | awk 'BEGIN{n=0} {lines[n++]=$0} END{print lines[int(n*0.95)]}')
    P99_LATENCY=$(echo "$LATENCIES" | sort -n | awk 'BEGIN{n=0} {lines[n++]=$0} END{print lines[int(n*0.99)]}')
else
    AVG_LATENCY=0; MAX_LATENCY=0; MIN_LATENCY=0; P95_LATENCY=0; P99_LATENCY=0
fi

# Distribuição de verdicts
APPROVED_COUNT=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$4=="APPROVED"{c++}END{print c+0}')
BLOCKED_COUNT=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$4=="BLOCKED"{c++}END{print c+0}')
CHALLENGE_COUNT=$(tail -n +2 "$RESULTS_FILE" | awk -F'\t' '$4=="CHALLENGE"{c++}END{print c+0}')

# ── Relatório final ────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║              RELATÓRIO DE STRESS TEST                ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  VOLUME"
echo -e "  ├── Total enviados:   ${YELLOW}$TOTAL${NC}"
echo -e "  ├── Sucesso (200):    ${GREEN}$SUCCESS${NC}"
echo -e "  ├── Erro (4xx/5xx):   ${RED}$FAIL${NC}"
echo -e "  └── Timeout (>5s):    ${RED}$TIMEOUT_COUNT${NC}"
echo ""
echo -e "  LATÊNCIA (ms)"
echo -e "  ├── Mínima:  ${GREEN}${MIN_LATENCY}ms${NC}"
echo -e "  ├── Média:   ${YELLOW}${AVG_LATENCY}ms${NC}"
echo -e "  ├── p95:     ${YELLOW}${P95_LATENCY}ms${NC}"
echo -e "  ├── p99:     ${YELLOW}${P99_LATENCY}ms${NC}"
echo -e "  └── Máxima:  ${RED}${MAX_LATENCY}ms${NC}"
echo ""
echo -e "  DECISÕES DO MOTOR"
echo -e "  ├── APPROVED:  ${GREEN}$APPROVED_COUNT${NC}"
echo -e "  ├── BLOCKED:   ${RED}$BLOCKED_COUNT${NC}"
echo -e "  └── CHALLENGE: ${YELLOW}$CHALLENGE_COUNT${NC}"
echo ""

# ── Avaliação do SLA ─────────────────────────────────────────────────────────
echo -e "${CYAN}[5/5] Avaliação do SLA (p99 < 80ms)...${NC}"
echo -e "─────────────────────────────────────────────────────"

SLA_PASS=true
if [ "$P99_LATENCY" -gt 80 ] 2>/dev/null; then
    echo -e "${RED}✗ SLA VIOLADO: p99=${P99_LATENCY}ms (limite: 80ms)${NC}"
    SLA_PASS=false
else
    echo -e "${GREEN}✓ SLA OK: p99=${P99_LATENCY}ms < 80ms${NC}"
fi

if [ "$TIMEOUT_COUNT" -gt 0 ]; then
    echo -e "${RED}✗ TIMEOUTS DETECTADOS: $TIMEOUT_COUNT requisições (>5s)${NC}"
    SLA_PASS=false
fi

FAIL_RATE=0
if [ "$TOTAL" -gt 0 ]; then
    FAIL_RATE=$(echo "scale=1; ($FAIL + $TIMEOUT_COUNT) * 100 / $TOTAL" | bc)
fi

if (( $(echo "$FAIL_RATE > 1.0" | bc -l) )); then
    echo -e "${RED}✗ TAXA DE ERRO: ${FAIL_RATE}% (limite: 1%)${NC}"
    SLA_PASS=false
else
    echo -e "${GREEN}✓ TAXA DE ERRO: ${FAIL_RATE}% < 1%${NC}"
fi

echo ""
echo -e "  Resultados completos: ${YELLOW}$RESULTS_FILE${NC}"
echo ""

# Cleanup
rm -rf "$PAYLOAD_DIR"

if [ "$SLA_PASS" = true ]; then
    echo -e "${GREEN}╔══════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  ✓ STRESS TEST APROVADO — SLA OK ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════╝${NC}"
    exit 0
else
    echo -e "${RED}╔══════════════════════════════════════╗${NC}"
    echo -e "${RED}║  ✗ STRESS TEST FALHOU — SLA VIOLADO  ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════╝${NC}"
    exit 1
fi

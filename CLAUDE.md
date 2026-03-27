# CLAUDE.md — ragnarok-antifraude

## O que é este projeto

Microsserviço antifraude independente do `ragnarok-core`. Recebe eventos do jogo (trocas, cliques, logins, farm, cash, instâncias, mercado) via REST e retorna uma decisão (`APPROVED` / `BLOCKED` / `CHALLENGE`) em menos de 80ms. Nunca trava a tela do jogador.

## Princípios invioláveis

1. **Fail-open**: Se qualquer coisa falhar (timeout, Redis down, exceção), a decisão é APPROVED. O jogo nunca para por culpa do antifraude.
2. **Latência < 80ms p99**: As 9 regras rodam em PARALELO com Virtual Threads (Java 21). Timeout hard de 50ms no motor. Audit log é assíncrono.
3. **Hexagonal (Ports & Adapters)**: Domain é puro Java, zero frameworks. Infrastructure implementa as ports. Application orquestra.
4. **Thread-safety**: FraudEvent e FraudDecision são records imutáveis. Regras são stateless — todo estado vem do Redis ou PostgreSQL.

## Stack

- Java 21 (Virtual Threads obrigatório)
- Spring Boot 3.2.5
- PostgreSQL 16 (dados frios: audit log, cooldowns, market prices seed)
- Redis 7 (dados quentes: anti-dupe locks, farm sessions, click timing, login country, market prices cache)
- Flyway (migrations)
- Caffeine (cache L1 in-process)
- Testcontainers (testes de integração)
- Docker multi-stage (JRE 21, ZGC, non-root)

## Arquitetura Hexagonal — regras de pacote

```
com.ragnarok.antifraude
├── domain/                     # PURO JAVA — zero imports de Spring/Redis/JPA
│   ├── model/                  # Records imutáveis: FraudEvent, FraudDecision, enums
│   ├── rule/                   # Interface FraudRule + RuleResult
│   └── port/
│       ├── in/                 # Use cases (interfaces que a infra chama)
│       └── out/                # Repositories (interfaces que o domain precisa)
│
├── application/                # Orquestração — pode importar domain, NUNCA infra
│   ├── service/                # FraudAnalysisService (motor paralelo), AuditLogService
│   └── rule/                   # 9 implementações de FraudRule (podem usar ports out)
│
└── infrastructure/             # Implementações concretas — pode importar tudo
    ├── adapter/
    │   ├── in/rest/            # Controllers, DTOs, Mappers
    │   └── out/
    │       ├── redis/          # RedisTransactionAdapter, RedisPlayerActivityAdapter
    │       └── persistence/    # JPA entities, Spring Data repositories, adapters
    ├── config/                 # AsyncConfig, RedisConfig, SecurityConfig
    └── scheduler/              # MarketPriceRefresher (@Scheduled)
```

**Regra de dependência**: domain → (nada) | application → domain | infrastructure → application + domain

## As 9 regras de fraude

| # | Classe | EventType | O que faz | Storage |
|---|--------|-----------|-----------|---------|
| 1 | AntiDupeRule | ITEM_TRADE | Redis SET NX no itemUuid, janela 500ms | Redis |
| 2 | DisproportionateTransferRule | ITEM_TRADE | Compara zenysValue com preço de mercado (ratio 100x/1000x) | Redis (cache de preços) |
| 3 | BotFarmTimeRule | FARM_HEARTBEAT | Farm contínuo 6h/12h/24h no mesmo mapa | Redis (HGET session) |
| 4 | BotClickSpeedRule | CLICK_ACTION | APS > threshold + stddev de latência robótica | Redis (sliding window) |
| 5 | RegistrationValidationRule | SESSION_LOGIN | Email verificado + maioridade | Payload only |
| 6 | CashSecurityRule | CASH_PURCHASE | CPF/CNPJ do titular vs dados de cobrança | Redis (CPF lookup) |
| 7 | InstanceCooldownRule | MAP_INSTANCE_ENTRY | Cooldown de dias/semanas para reentrada | PostgreSQL (indexed) |
| 8 | ImpossibleTravelRule | SESSION_LOGIN | País mudou em < 2h → DROP_SESSION | Redis (HGET last country) |
| 9 | MarketMonopolyRule | MARKET_PURCHASE | Compra > 80%/95% do estoque → ALERT/FLAG | Payload math |

Cada regra implementa `FraudRule`:
- `ruleId()` → identificador único (ex: "ANTI_DUPE")
- `eventTypes()` → lista de eventTypes que a regra avalia
- `priority()` → ordem de precedência quando várias regras disparam
- `evaluate(FraudEvent)` → RuleResult (APPROVED/BLOCKED/CHALLENGE + reason)

Todas as regras têm try/catch interno com fail-open. Se uma regra lançar exceção, retorna RuleResult.approved().

## Motor paralelo (FraudAnalysisService)

```
1. Recebe FraudEvent
2. Filtra regras aplicáveis pelo eventType
3. Executa todas em paralelo via CompletableFuture + VirtualThreadPerTaskExecutor
4. allOf() com timeout de 50ms
5. Regras que não retornaram a tempo → ignoradas (fail-open)
6. Combina resultados: pior veredicto vence (BLOCKED > CHALLENGE > APPROVED)
7. Persiste audit log de forma ASSÍNCRONA (fora do caminho crítico)
8. Retorna FraudDecision
```

## Endpoints

### Análise (chamado pelo ragnarok-core)
- `POST /api/fraud/analyze` → recebe FraudEventRequest, retorna FraudDecisionResponse

### Sync de estado (fire-and-forget do core)
- `POST /api/fraud/state/player/{id}/map-leave?mapId=xxx`
- `POST /api/fraud/state/player/{id}/login?countryCode=BR`
- `POST /api/fraud/state/player/{id}/instance-enter` (body: mapId, cooldownSeconds)
- `POST /api/fraud/state/player/{id}/registration` (body: emailVerified, ageVerified, document)

### Admin / Dashboard
- `GET /api/fraud/admin/player/{id}/history`
- `GET /api/fraud/admin/player/{id}/blocks?hours=24`

### Health
- `GET /api/fraud/health`

## Segurança

Todos os endpoints (exceto /health) exigem header `X-API-Key` validado por ApiKeyFilter.
A key é configurada via variável de ambiente `ANTIFRAUDE_API_KEY`.

## Comandos

```bash
# Build
./mvnw clean install

# Rodar local (porta 8081)
# IMPORTANTE: Docker mapeia PostgreSQL na porta 5433 externa (não 5432)
docker compose up -d postgres redis
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ragnarok_antifraude_db \
DB_PASS=postgre ANTIFRAUDE_API_KEY=dev-key-123 ./mvnw spring-boot:run

# Docker completo (antifraude + postgres + redis + prometheus + grafana)
docker compose up -d

# Parar sem perder dados (use sempre stop, nunca down -v)
docker compose stop

# Testes unitários
./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest"

# Testes de simulação
./mvnw test -Dtest="SimulationControllerTest,SimulationServiceTest,SimulationResultTest"

# Testes de integração (requer Docker Desktop ativo)
./mvnw test -Dtest="FraudControllerIntegrationTest"

# Stress test
./scripts/stress_test.sh 1000 50
```

## Observabilidade

- **Prometheus**: http://localhost:9090 — scrape a cada 15s em `/actuator/prometheus`
- **Grafana**: http://localhost:3000 (admin/admin) — dashboards provisionados automaticamente
- **Health**: http://localhost:8081/api/fraud/health (público, sem API key)
- **Actuator**: http://localhost:8081/actuator/health, /actuator/prometheus
- **Swagger**: http://localhost:8081/swagger-ui.html (sem API key)

## Integração com ragnarok-core

O ragnarok-core chama o antifraude via HTTP. Os arquivos de integração ficam em `integration-core/` neste repositório e devem ser copiados para o ragnarok-core:

| Arquivo | Destino no ragnarok-core |
|---|---|
| `integration-core/FraudClient.java` | `com/ragnarok/infrastructure/antifraude/FraudClient.java` |
| `integration-core/UsageExamples.java` | `com/ragnarok/infrastructure/antifraude/UsageExamples.java` |
| `integration-core/application-antifraude.yml` | Merge no `application.yml` do core |

**FraudClient** usa RestTemplate (sync, 50ms read timeout) para `/api/fraud/analyze` e WebClient (fire-and-forget assíncrono) para os endpoints de estado. Tem `@CircuitBreaker` do Resilience4j com fallback que retorna `FALLBACK_APPROVED`. O construtor padrão de `FraudDecision` retorna `UNKNOWN` para evitar aprovações silenciosas em falha de deserialização.

O core é Java 17 — o FraudClient precisa ser compatível com Java 17. O antifraude em si é Java 21.

## Análise do Estado Atual (2026-03-27)

### Status dos testes

| Suite | Comando | Status | Pré-requisito |
|---|---|---|---|
| Unitários (regras) | `./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest"` | ✅ 25/25 | Nenhum |
| Simulação | `./mvnw test -Dtest="SimulationControllerTest,SimulationServiceTest,SimulationResultTest"` | ✅ 6/6 | Nenhum |
| Integração | `./mvnw test -Dtest="FraudControllerIntegrationTest"` | ❌ Requer Docker Engine ativo | Docker Desktop rodando |

O teste de integração usa Testcontainers (sobe PostgreSQL + Redis via Docker automaticamente).
Para rodá-lo: abrir o Docker Desktop e aguardar o engine iniciar, depois executar o comando acima.

O erro `TypeTag::UNKNOWN` que aparece ocasionalmente é causado por lock no JAR enquanto a aplicação está rodando.
Solução: parar o processo antes de fazer `./mvnw clean install`.

### Bugs conhecidos (a corrigir)

| Prioridade | Problema | Localização |
|---|---|---|
| 🔴 Alta | `FraudClient.java` no ragnarok-core é um **stub** — zero eventos chegam a este serviço hoje | `ragnarok-core/.../antifraude/FraudClient.java` — substituir pelo `integration-core/FraudClient.java` |
| 🔴 Alta | Instance cooldown não persiste no PostgreSQL — Redis restart apaga todos os cooldowns | `RedisPlayerActivityAdapter` — `getInstanceCooldownExpiry()` sem fallback para JPA |
| 🔴 Alta | `recordInstanceEntry` não grava no PostgreSQL (source of truth incompleto) | `RedisPlayerActivityAdapter` — `recordInstanceEntry()` com TODO |
| 🟡 Média | `ExecutorService` do motor paralelo sem `@PreDestroy` — nunca fechado no shutdown | `FraudAnalysisService` linha 51 |
| 🟡 Média | `networkLatencyMs` do payload nunca usado na análise (lido mas descartado) | `BotClickSpeedRule` |
| 🟡 Média | `ApiKeyFilter` usa `String.equals` em vez de comparação constant-time (risco timing attack) | `ApiKeyFilter` linha 56 |
| 🟡 Média | `saveRegistrationData` não persiste `emailVerified`/`ageVerified` (só grava o documento) | `RedisPlayerActivityAdapter` linha 160 |
| 🟢 Baixa | Testes unitários faltando para regras 4 (BotClickSpeed), 6 (CashSecurity), 7 (InstanceCooldown) | `FraudRulesUnitTest` |

### Cobertura das 9 regras por testes unitários

| # | Regra | Testada unitariamente |
|---|---|---|
| 1 | AntiDupeRule | ✅ |
| 2 | DisproportionateTransferRule | ✅ |
| 3 | BotFarmTimeRule | ✅ |
| 4 | BotClickSpeedRule | ❌ |
| 5 | RegistrationValidationRule | ✅ |
| 6 | CashSecurityRule | ❌ |
| 7 | InstanceCooldownRule | ❌ |
| 8 | ImpossibleTravelRule | ✅ |
| 9 | MarketMonopolyRule | ✅ |

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

## Falhas conhecidas do código anterior (corrigir)

1. **pom.xml**: `java.version` era 17, deve ser 21
2. **Dockerfile**: HEALTHCHECK usava `curl` (não existe na imagem JRE), trocar por `wget`
3. **FraudClient.java** (no ragnarok-core): `analyze()` tem try/catch que impede o CircuitBreaker de abrir. Remover o try/catch — deixar a exceção propagar para o @CircuitBreaker
4. **FraudClient.java**: Construtor padrão de FraudDecision retorna APPROVED — falhas de deserialização viram aprovações silenciosas. Trocar para estado UNKNOWN
5. **FraudClient.java**: WebClient + .block() é anti-pattern. Usar RestClient (se core for Boot 3.2+) ou RestTemplate
6. **docker-compose.yml**: Referencia `docker/init-multiple-dbs.sh` que não existia. Criar o script
7. **Timeouts**: Remover .block(Duration) do FraudClient. Manter apenas o TimeLimiter do Resilience4j

## Comandos

```bash
# Build
./mvnw clean install

# Rodar local (porta 8081)
DB_PASS=postgre ANTIFRAUDE_API_KEY=dev-key-123 java -jar target/ragnarok-antifraude-0.0.1-SNAPSHOT.jar

# Docker
docker compose up -d

# Testes unitários
./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest"

# Testes de integração (requer Docker)
./mvnw test -Dtest="FraudControllerIntegrationTest"

# Stress test
./scripts/stress_test.sh 1000 50
```

## Integração com ragnarok-core

O ragnarok-core chama o antifraude via HTTP. O `FraudClient.java` e `UsageExamples.java` ficam DENTRO do ragnarok-core (não deste projeto). Este projeto é só o microsserviço que recebe e responde.

O core atual é Java 17 — o FraudClient precisa ser compatível com Java 17. O antifraude em si é Java 21.

## Análise do Estado Atual (2026-03-24)

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
| 🔴 Alta | Instance cooldown não persiste no PostgreSQL — Redis restart apaga todos os cooldowns | `RedisPlayerActivityAdapter:139` (fallback ausente) |
| 🔴 Alta | `recordInstanceEntry` não grava no PostgreSQL (source of truth incompleto) | `RedisPlayerActivityAdapter:147` |
| 🟡 Média | `ExecutorService` do motor paralelo nunca é fechado no shutdown | `FraudAnalysisService:46` |
| 🟡 Média | `networkLatencyMs` lido mas nunca usado em `BotClickSpeedRule` | `BotClickSpeedRule:43` |
| 🟡 Média | `ApiKeyFilter` usa `String.equals` em vez de constant-time comparison | `ApiKeyFilter:56` |
| 🟡 Média | `saveRegistrationData` não persiste `emailVerified`/`ageVerified` | `RedisPlayerActivityAdapter:164` |
| 🟢 Baixa | TODO comment desatualizado (implementação já existe) | `RedisPlayerActivityAdapter:21` |
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

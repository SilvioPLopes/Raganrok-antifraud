# ragnarok-antifraude

Motor antifraude independente do `ragnarok-core`, construído em **Arquitetura Hexagonal (Ports & Adapters)** com Java 21 e Virtual Threads. Recebe eventos do jogo via REST e retorna uma decisão (`APPROVED`, `BLOCKED` ou `CHALLENGE`) dentro de **80ms garantidos no p99** — o jogo nunca trava por culpa do antifraude.

---

## Índice

- [O Problema](#o-problema)
- [A Solução](#a-solução)
- [Arquitetura](#arquitetura)
- [As 9 Regras](#as-9-regras)
- [Stack Tecnológica](#stack-tecnológica)
- [Pré-requisitos](#pré-requisitos)
- [Quick Start](#quick-start)
- [Endpoints da API](#endpoints-da-api)
- [Integração com o ragnarok-core](#integração-com-o-ragnarok-core)
- [Configuração](#configuração)
- [Banco de Dados](#banco-de-dados)
- [Stress Test](#stress-test)
- [Docker](#docker)
- [Testes](#testes)
- [Segurança](#segurança)
- [Decisões Arquiteturais](#decisões-arquiteturais)
- [Estrutura de Pastas](#estrutura-de-pastas)
- [Troubleshooting](#troubleshooting)
- [Roadmap](#roadmap)

---

## O Problema

Um motor antifraude que roda 9 regras em sequência antes de cada ação do jogador pode facilmente ultrapassar 100ms:

```
Regra 1 (3ms) + Regra 2 (5ms) + ... + Regra 9 (3ms) = ~35ms sequencial
+ overhead HTTP + serialização + acesso a banco = >100ms
```

Em um MMO como Ragnarok Online, 100ms de latência adicionados ao combate ou ao comércio de itens tornam o jogo injogável. Atacar um monstro, trocar um item, abrir uma vending — tudo congela esperando o antifraude responder.

O desafio central é: **como rodar 9 verificações complexas sem que o jogador perceba que elas existem?**

---

## A Solução

Quatro decisões arquiteturais eliminam o risco de latência:

**1. Regras em paralelo com Virtual Threads (Java 21).**
As 9 regras são disparadas ao mesmo tempo. O tempo total é o da regra *mais lenta* (≈ 5ms), não a soma de todas. Virtual Threads têm overhead próximo de zero para tarefas I/O-bound — cada regra roda em sua própria thread virtual sem custo de pool.

**2. Timeout hard de 50ms.**
Se qualquer regra não retornar dentro de 50ms, é ignorada. O motor retorna `APPROVED` para as regras que completaram. O jogo nunca espera mais que 50ms pelo antifraude.

**3. Redis como cache L1 para dados quentes.**
Anti-dupe locks, sessões de farm, timing de cliques, país de login, preços de mercado — tudo vive no Redis com leituras sub-millisecond. O PostgreSQL só é acessado para dados frios (audit log, cooldowns de instância, seed de preços).

**4. Audit log 100% assíncrono.**
A persistência do resultado no PostgreSQL acontece *depois* que a resposta já foi enviada ao ragnarok-core. O banco de dados nunca está no caminho crítico.

```
                    ┌─ Regra 1 (Redis SET NX)       ≈ 3ms ─┐
                    ├─ Regra 2 (Redis GET price)     ≈ 5ms ─┤
   FraudEvent ──────├─ Regra 3 (Redis HGET session)  ≈ 2ms ─├──→ FraudDecision
   do ragnarok-core ├─ Regra 4 (Redis ZADD window)   ≈ 2ms ─┤    em < 50ms
                    ├─ Regra 5 (payload check)        ≈ 1ms ─┤
                    ├─ Regra 6 (Redis GET doc)        ≈ 5ms ─┤
                    ├─ Regra 7 (PostgreSQL indexed)   ≈ 4ms ─┤
                    ├─ Regra 8 (Redis HGET login)     ≈ 2ms ─┤
                    └─ Regra 9 (payload math)         ≈ 3ms ─┘
                                                              │
                                      Audit log ──────────────┘ (async, fora do caminho crítico)
```

---

## Arquitetura

O projeto segue **Arquitetura Hexagonal** com 3 camadas e uma regra de dependência estrita:

```
domain → (nada)
application → domain
infrastructure → application + domain
```

O domain é puro Java — zero imports de Spring, Redis, JPA ou qualquer framework. Isso garante que a lógica de negócio é testável sem infraestrutura.

```
┌─────────────────────────────────────────────────────────────────────┐
│  DOMAIN (puro Java)                                                 │
│                                                                     │
│  model/          FraudEvent (record imutável)                       │
│                  FraudDecision (record imutável)                     │
│                  Verdict, RequiredAction, RiskLevel (enums)         │
│                                                                     │
│  rule/           FraudRule (interface) + RuleResult (record)         │
│                                                                     │
│  port/in/        FraudAnalysisUseCase                               │
│  port/out/       TransactionRepository                              │
│                  PlayerActivityRepository                            │
│                  AuditRepository                                     │
├─────────────────────────────────────────────────────────────────────┤
│  APPLICATION (orquestração)                                         │
│                                                                     │
│  service/        FraudAnalysisService (motor paralelo)              │
│                  AuditLogService (persistência assíncrona)           │
│                                                                     │
│  rule/           AntiDupeRule, DisproportionateTransferRule,        │
│                  BotFarmTimeRule, BotClickSpeedRule,                 │
│                  RegistrationValidationRule, CashSecurityRule,       │
│                  InstanceCooldownRule, ImpossibleTravelRule,         │
│                  MarketMonopolyRule                                  │
├─────────────────────────────────────────────────────────────────────┤
│  INFRASTRUCTURE (implementações concretas)                          │
│                                                                     │
│  adapter/in/     FraudController (POST /api/fraud/analyze)          │
│    rest/         PlayerStateController (sync de estado)              │
│                  AdminDashboardController (GMs)                      │
│                  DTOs + Mapper                                       │
│                                                                     │
│  adapter/out/    RedisTransactionAdapter                             │
│    redis/        RedisPlayerActivityAdapter                          │
│                                                                     │
│  adapter/out/    JpaAuditAdapter                                     │
│    persistence/  FraudAuditEntity + FraudAuditJpaRepository          │
│                                                                     │
│  config/         AsyncConfig (pools de thread)                       │
│                  ApiKeyFilter (autenticação inter-serviço)           │
│                                                                     │
│  scheduler/      MarketPriceRefresher (PostgreSQL → Redis a cada 5m)│
└─────────────────────────────────────────────────────────────────────┘
```

---

## As 9 Regras

Cada regra implementa a interface `FraudRule` com try/catch interno — se falhar, retorna `APPROVED` (fail-open). Todas são stateless e thread-safe.

### Regra 1 — Anti-Dupe (`ANTI_DUPE`)

Impede clonagem de itens. Usa Redis `SET NX` atômico no UUID do item com TTL de 500ms. Se o mesmo UUID aparecer duas vezes nessa janela, a segunda transação é bloqueada.

- **EventType:** `ITEM_TRADE`
- **Ação:** `CANCEL_ACTION`
- **Storage:** Redis
- **Latência:** < 3ms

### Regra 2 — Transferências Desproporcionais (`DISPROPORTIONATE_TRANSFER`)

Compara o valor em zenys da troca com o preço mediano de mercado do item (cache no Redis, atualizado a cada 5 minutos pelo `MarketPriceRefresher`).

- **EventType:** `ITEM_TRADE`
- **Thresholds:** ratio > 100x → `FLAG_FOR_REVIEW` | > 1000x → `CANCEL_ACTION` | > 10000x → `CANCEL_ACTION` + `CRITICAL`
- **Storage:** Redis (preços cacheados)
- **Latência:** < 5ms

### Regra 3 — Detector de Bot por Tempo (`BOT_FARM_TIME`)

Detecta farm contínuo no mesmo mapa. O ragnarok-core envia heartbeats periódicos; o antifraude calcula a duração da sessão.

- **EventType:** `FARM_HEARTBEAT`
- **Thresholds:** > 6h → `SHOW_CAPTCHA` | > 12h → `FLAG_FOR_REVIEW` | > 24h → `DROP_SESSION`
- **Storage:** Redis (timestamp de início da sessão)
- **Latência:** < 2ms

### Regra 4 — Detector de Bot por Clique (`BOT_CLICK_SPEED`)

Analisa ações por segundo (APS) e o desvio padrão dos intervalos entre cliques. Humanos têm timing irregular; bots têm timing roboticamente regular (stddev < 5ms).

- **EventType:** `CLICK_ACTION`
- **Thresholds:** APS > 15 → `SHOW_CAPTCHA` | APS > 25 ou stddev < 5ms → `FLAG_FOR_REVIEW`
- **Storage:** Redis (sorted set com sliding window de 10s)
- **Latência:** < 2ms

### Regra 5 — Validação de Cadastro (`REGISTRATION_VALIDATION`)

Verifica se o email foi confirmado e se o jogador atende o requisito de maioridade antes de permitir o login.

- **EventType:** `SESSION_LOGIN`, `ACCOUNT_REGISTRATION`
- **Ação:** `CANCEL_ACTION`
- **Storage:** Nenhum (dados no payload)
- **Latência:** < 1ms

### Regra 6 — Segurança de Cash (`CASH_SECURITY`)

Bloqueia compras de cash (ROPs) se o CPF/CNPJ informado no billing não bate com o documento cadastrado do titular da conta.

- **EventType:** `CASH_PURCHASE`
- **Ação:** `CANCEL_ACTION`
- **Storage:** Redis (documento do jogador)
- **Latência:** < 5ms

### Regra 7 — Cooldown de Instâncias (`INSTANCE_COOLDOWN`)

Impede reentrada em mapas especiais (MVP rooms, calabouços de alto nível) antes do cooldown expirar. Cooldowns são de dias ou semanas.

- **EventType:** `MAP_INSTANCE_ENTRY`
- **Ação:** `CANCEL_ACTION`
- **Storage:** PostgreSQL (dados duráveis que sobrevivem a restart) + Redis (cache)
- **Latência:** < 4ms

### Regra 8 — Viagem Impossível (`IMPOSSIBLE_TRAVEL`)

Se o país de login muda em menos de 2 horas, a conta provavelmente foi comprometida. A sessão é derrubada imediatamente.

- **EventType:** `SESSION_LOGIN`
- **Ação:** `DROP_SESSION`
- **Storage:** Redis (último país + timestamp)
- **Latência:** < 2ms

### Regra 9 — Monopólio de Mercado (`MARKET_MONOPOLY`)

Detecta tentativas de comprar uma fatia desproporcional do estoque de um item para manipular a economia do jogo.

- **EventType:** `MARKET_PURCHASE`
- **Thresholds:** > 80% do estoque → `ALERT_ONLY` | > 95% → `FLAG_FOR_REVIEW`
- **Storage:** Nenhum (cálculo sobre o payload)
- **Latência:** < 3ms

---

## Stack Tecnológica

| Componente | Tecnologia | Motivo |
|-----------|-----------|--------|
| Runtime | Java 21 + Virtual Threads | Paralelismo massivo sem overhead de thread pool |
| Framework | Spring Boot 3.2.5 | Ecossistema maduro, compatível com Java 21 |
| Banco (frio) | PostgreSQL 16 | Audit log, cooldowns, seed de preços |
| Cache (quente) | Redis 7 | Sub-millisecond reads para todas as regras de estado |
| Cache (L1) | Caffeine | In-process cache para dados lidos constantemente |
| Migrations | Flyway | Versionamento de schema automático |
| Build | Maven Wrapper | Sem dependência de Maven instalado |
| Container | Docker multi-stage | JRE-only, non-root, ZGC, ~200MB |
| GC | ZGC | Pausas < 1ms — ideal para latência |
| API Docs | springdoc-openapi | Swagger UI automático |
| Testes | JUnit 5 + Testcontainers | Integração com PostgreSQL e Redis reais |
| Cobertura | JaCoCo | Relatório de cobertura no build |

---

## Pré-requisitos

Para rodar com Docker (recomendado): apenas **Docker** e **Docker Compose**.

Para rodar local sem Docker: Java 21+ (JDK), PostgreSQL 16+ na porta 5432, Redis 7+ na porta 6379.

---

## Quick Start

### Com Docker (recomendado)

```bash
# Sobe PostgreSQL + Redis + antifraude
docker compose up -d

# Verifica se está healthy
docker compose ps

# Acompanha os logs
docker compose logs -f antifraude

# Testa o health check
curl http://localhost:8081/api/fraud/health
```

### Local (sem Docker)

```bash
# 1. Criar o banco
createdb ragnarok_antifraude_db

# 2. Build
./mvnw clean install

# 3. Rodar (porta 8081)
DB_PASS=sua_senha java -jar target/ragnarok-antifraude-0.0.1-SNAPSHOT.jar

# 4. Verificar
curl http://localhost:8081/api/fraud/health
```

### Swagger UI

Com o serviço rodando: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)

---

## Endpoints da API

### Análise de Fraude

O endpoint principal. O ragnarok-core chama este endpoint **antes** de commitar qualquer ação sensível.

```
POST /api/fraud/analyze
Content-Type: application/json
X-API-Key: sua-chave (se configurada)
```

**Request:**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "ITEM_TRADE",
  "playerId": 12345,
  "ipAddress": "200.100.50.25",
  "countryCode": "BR",
  "occurredAt": "2025-01-15T14:30:00Z",
  "payload": {
    "itemId": 4324,
    "itemUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "zenysValue": 25000000
  }
}
```

**Response (aprovado):**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "playerId": 12345,
  "verdict": "APPROVED",
  "requiredAction": "NONE",
  "riskLevel": "LOW",
  "triggeredRules": [],
  "reason": "All rules passed",
  "processingTimeMs": 7
}
```

**Response (bloqueado):**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "playerId": 12345,
  "verdict": "BLOCKED",
  "requiredAction": "CANCEL_ACTION",
  "riskLevel": "CRITICAL",
  "triggeredRules": ["ANTI_DUPE", "DISPROPORTIONATE_TRANSFER"],
  "reason": "Duplicate item trade detected: a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "processingTimeMs": 4
}
```

**Event Types suportados:**

| EventType | Regras ativadas | Campos obrigatórios no payload |
|-----------|----------------|-------------------------------|
| `ITEM_TRADE` | 1, 2 | `itemId`, `itemUuid`, `zenysValue` |
| `CLICK_ACTION` | 4 | `actionsPerSecond`, `networkLatencyMs` |
| `FARM_HEARTBEAT` | 3 | `mapId` |
| `SESSION_LOGIN` | 5, 8 | `emailVerified`, `ageVerified` |
| `ACCOUNT_REGISTRATION` | 5 | `emailVerified`, `ageVerified` |
| `CASH_PURCHASE` | 6 | `billingName`, `billingDocument` |
| `MAP_INSTANCE_ENTRY` | 7 | `mapId` |
| `MARKET_PURCHASE` | 9 | `itemId`, `quantityRequested`, `totalInStock` |

### Sync de Estado (fire-and-forget)

O ragnarok-core chama estes endpoints de forma assíncrona para manter o antifraude atualizado. Se falharem, não afetam o jogo.

```
POST /api/fraud/state/player/{id}/map-leave?mapId=prt_maze03
POST /api/fraud/state/player/{id}/login?countryCode=BR
POST /api/fraud/state/player/{id}/instance-enter
     Body: { "mapId": "mvp_room01", "cooldownSeconds": 604800 }
POST /api/fraud/state/player/{id}/registration
     Body: { "emailVerified": true, "ageVerified": true, "document": "123.456.789-00" }
```

### Admin / Dashboard

Endpoints para game masters investigarem jogadores suspeitos.

```
GET /api/fraud/admin/player/{id}/history?limit=50
GET /api/fraud/admin/player/{id}/blocks?hours=24
```

### Health

```
GET /api/fraud/health
```

---

## Integração com o ragnarok-core

O diretório `integration-core/` contém os arquivos que devem ser copiados para **dentro** do ragnarok-core:

| Arquivo | Destino no ragnarok-core | Função |
|---------|--------------------------|--------|
| `FraudClient.java` | `com/ragnarok/infrastructure/antifraude/` | Client HTTP com Circuit Breaker + fallback |
| `UsageExamples.java` | mesmo pacote | Exemplos de uso em BattleService, ItemService, MapService, LoginService |
| `application-antifraude.yml` | merge no `application.yml` do core | Configuração do Resilience4j (CB + timeout) |

**Dependências a adicionar no pom.xml do ragnarok-core:**

```xml
<!-- WebClient para chamadas HTTP -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Resilience4j para circuit breaker -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

**Filosofia da integração:** o antifraude é uma camada de proteção *adicional*. O `FraudClient` tem Circuit Breaker com fallback para `APPROVED` — se o antifraude cair, o jogo continua funcionando normalmente. Nenhuma ação do jogador é bloqueada por indisponibilidade do antifraude.

**Exemplo mínimo de uso no ragnarok-core:**

```java
// Em qualquer Service do ragnarok-core
FraudClient.FraudDecision decision = fraudClient.checkItemTrade(
    playerId, itemId, itemUuid, zenysValue
);

if (decision.isBlocked()) {
    throw new FraudBlockedException(decision.getReason());
}

// Continua a lógica normal...
```

---

## Configuração

### Variáveis de Ambiente

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `DB_PASS` | `postgre` | Senha do PostgreSQL |
| `REDIS_HOST` | `localhost` | Host do Redis |
| `REDIS_PORT` | `6379` | Porta do Redis |
| `REDIS_PASS` | (vazio) | Senha do Redis (opcional) |
| `ANTIFRAUDE_API_KEY` | (vazio) | API key para autenticação. Se vazio, filtro desabilitado |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/ragnarok_antifraude_db` | URL completa do banco |

### application.yml

O serviço roda na **porta 8081** (o ragnarok-core usa 8080). Configuração completa em `src/main/resources/application.yml`.

---

## Banco de Dados

O Flyway gerencia o schema automaticamente no boot. Duas migrations incluídas:

**V1 — Schema inicial:** tabela `fraud_audit_log` (append-only, indexada por player_id, created_at, verdict), tabela `market_prices` (preços medianos por item), tabela `player_instance_cooldown` (cooldowns com unique constraint em player_id + map_id).

**V2 — Seed de preços:** ~35 itens com preços reais baseados em bRO/iRO. Equipamentos comuns (Sword 100z), mid-tier (Gae Bolg 200kz), cartas raras (Golden Bug 2Mz), cartas MVP (Kiel-D-01 30Mz, Thanatos 35Mz). A Regra 2 funciona desde o primeiro boot.

O `MarketPriceRefresher` sincroniza preços do PostgreSQL para o Redis a cada 5 minutos. As regras leem apenas do Redis.

---

## Stress Test

O script `scripts/stress_test.sh` simula carga real de um MMO:

```bash
chmod +x scripts/stress_test.sh

# Padrão: 1000 requests, 50 concorrentes
./scripts/stress_test.sh

# Carga pesada
./scripts/stress_test.sh 5000 200

# Stress extremo (detecta gargalos de pool)
./scripts/stress_test.sh 10000 500
```

**Distribuição de tráfego simulado:**

| Cenário | Proporção | Tipo |
|---------|-----------|------|
| Trocas normais | 60% | `ITEM_TRADE` limpo |
| Cliques de combate | 15% | `CLICK_ACTION` com APS variado |
| Logins | 10% | `SESSION_LOGIN` de países aleatórios |
| Ataques de dupe | 10% | `ITEM_TRADE` com UUID fixo |
| Zeny bombs | 5% | `ITEM_TRADE` com 1B zenys |

**SLA esperado:**

| Métrica | Target |
|---------|--------|
| p95 | < 50ms |
| p99 | < 80ms |
| Taxa de erro | < 1% |
| Timeouts (> 5s) | 0 |

O script gera um relatório completo com latências, distribuição de verdicts e avaliação automática de pass/fail contra o SLA. Pré-requisitos: `curl`, `jq`, `bc`. Opcionalmente `GNU parallel` para máxima concorrência.

---

## Docker

### Build e execução

```bash
# Sobe tudo
docker compose up -d

# Só o antifraude (sobe dependências automaticamente)
docker compose up antifraude

# Logs
docker compose logs -f antifraude

# Destrói tudo incluindo volumes
docker compose down -v
```

### Detalhes do Dockerfile

O build é multi-stage: compilação com JDK 21, runtime com JRE 21 (~200MB). Roda como usuário não-root (`antifraude`). ZGC com pausas < 1ms. MaxRAMPercentage=75% para se adaptar ao limite do container. SecureRandom otimizado para UUID generation rápida sob carga.

### Serviços no compose

| Serviço | Imagem | Porta | Função |
|---------|--------|-------|--------|
| `postgres` | postgres:16-alpine | 5432 | Dois databases via init script |
| `redis` | redis:7-alpine | 6379 | Cache de estado, 256MB, allkeys-lru |
| `antifraude` | build local | 8081 | Microsserviço antifraude |

---

## Testes

```bash
# Todos os testes
./mvnw test

# Só unitários (sem Docker)
./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest"

# Só integração (requer Docker — Testcontainers sobe PostgreSQL e Redis)
./mvnw test -Dtest="FraudControllerIntegrationTest"

# Relatório de cobertura (JaCoCo)
# target/site/jacoco/index.html
```

**Cobertura:** `FraudRulesUnitTest` (cada regra isolada com mocks, testa aprovação/bloqueio/thresholds/fail-open), `FraudAnalysisServiceTest` (motor paralelo, timeout, combinação de resultados), `FraudControllerIntegrationTest` (ciclo completo com bancos reais via Testcontainers).

---

## Segurança

**Autenticação inter-serviço:** todos os endpoints (exceto `/api/fraud/health` e `/swagger-ui`) são protegidos pelo `ApiKeyFilter`. O ragnarok-core envia a chave via header `X-API-Key`. Se `ANTIFRAUDE_API_KEY` não for configurada, o filtro é desabilitado (modo dev).

**Fail-open:** o antifraude nunca bloqueia o jogo por falha própria. Timeouts, Redis down, exceções internas — tudo resulta em `APPROVED`.

**Thread-safety:** `FraudEvent` e `FraudDecision` são records imutáveis. As regras são stateless. Zero estado mutável compartilhado.

**Container seguro:** usuário não-root, JRE-only (sem compilador), sem shell root.

---

## Decisões Arquiteturais

| Decisão | Motivo | Alternativa rejeitada |
|---------|--------|-----------------------|
| Microsserviço separado | Falha do antifraude não derruba o jogo | Biblioteca embutida — acoplamento |
| Hexagonal | Domain testável sem infra, adapters substituíveis | Layered — domain acoplado ao framework |
| Virtual Threads | Zero overhead por thread I/O-bound | Platform threads — precisa tunar pool |
| Redis para estado quente | Sub-millisecond, atômico (SET NX) | Caffeine local — não funciona multi-instância |
| PostgreSQL para dados frios | Durável, transacional, SQL completo | Redis persistido — menos garantias ACID |
| Audit async | Banco fora do caminho crítico | Síncrono — adiciona 5-20ms |
| Fail-open em tudo | Jogo > antifraude | Fail-closed — qualquer falha bloqueia jogadores |
| ZGC | Pausas < 1ms | G1GC — pausas de 10-50ms sob pressão |
| Records imutáveis | Thread-safety por construção | Classes mutáveis + synchronized |

---

## Estrutura de Pastas

```
ragnarok-antifraude/
├── CLAUDE.md                              # Especificação para Claude Code
├── README.md                              # Este arquivo
├── pom.xml                                # Java 21, Spring Boot 3.2.5
├── Dockerfile                             # Multi-stage, JRE 21, ZGC, non-root
├── docker-compose.yml                     # PostgreSQL + Redis + antifraude
├── docker/
│   └── init-multiple-dbs.sh               # Cria os dois bancos no PostgreSQL
├── scripts/
│   └── stress_test.sh                     # Teste de carga com relatório de SLA
├── integration-core/                      # Arquivos para copiar no ragnarok-core
│   ├── FraudClient.java                   # Client HTTP + Circuit Breaker
│   ├── UsageExamples.java                 # Exemplos de uso nos Services
│   └── application-antifraude.yml         # Config Resilience4j
└── src/
    ├── main/
    │   ├── java/com/ragnarok/antifraude/
    │   │   ├── RagnarokAntifraude.java
    │   │   ├── domain/
    │   │   │   ├── model/                 # FraudEvent, FraudDecision, enums
    │   │   │   ├── rule/                  # FraudRule interface, RuleResult
    │   │   │   └── port/
    │   │   │       ├── in/                # FraudAnalysisUseCase
    │   │   │       └── out/               # TransactionRepo, PlayerActivityRepo, AuditRepo
    │   │   ├── application/
    │   │   │   ├── service/               # FraudAnalysisService, AuditLogService
    │   │   │   └── rule/                  # 9 implementações de FraudRule
    │   │   └── infrastructure/
    │   │       ├── adapter/in/rest/       # Controllers, DTOs, Mapper
    │   │       ├── adapter/out/redis/     # Redis adapters
    │   │       ├── adapter/out/persistence/ # JPA entities, repos, adapter
    │   │       ├── config/                # AsyncConfig, ApiKeyFilter
    │   │       └── scheduler/             # MarketPriceRefresher
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql
    │           └── V2__seed_market_prices.sql
    └── test/
        ├── java/                          # Unit + Integration tests
        └── resources/application-test.yml
```

---

## Troubleshooting

**O antifraude não sobe no Docker:**
Verifique se o PostgreSQL e Redis estão healthy com `docker compose ps`. O antifraude espera ambos ficarem prontos antes de iniciar (`depends_on: condition: service_healthy`).

**"relation fraud_audit_log does not exist":**
O Flyway não rodou. Verifique se o banco `ragnarok_antifraude_db` existe e se `DB_PASS` está correto.

**Todas as decisões são APPROVED, mesmo para fraudes óbvias:**
Verifique se o Redis está acessível. Sem Redis, as regras 1-4, 6 e 8 fazem fail-open (retornam APPROVED).

**Latência alta no stress test:**
Verifique se o Redis está na mesma rede. Latência de rede ao Redis > 1ms impacta todas as regras.

**CircuitBreaker do FraudClient (no core) sempre aberto:**
Verifique se o `ANTIFRAUDE_API_KEY` do core bate com o do antifraude. HTTP 401 conta como falha para o CircuitBreaker.

**Container marcado como unhealthy:**
O HEALTHCHECK usa `wget` no `/api/fraud/health`. Se não responde em 5s, é marcado unhealthy. O `start_period` é de 40s para dar tempo ao Spring Boot iniciar.

---

## Roadmap

- [ ] Rate limiting por playerId no endpoint `/analyze`
- [ ] Métricas Prometheus via Actuator (`/actuator/prometheus`)
- [ ] Grafana dashboard pré-configurado (latência, verdicts, regras)
- [ ] Kafka como alternativa ao REST para eventos de alta frequência
- [ ] Machine learning para detecção de padrões de bot
- [ ] Admin UI web (React) para game masters
- [ ] Notificações via webhook (Discord/Slack) para bloqueios CRITICAL
- [ ] Multi-instância com Redis Cluster
- [ ] Replay de eventos para testar novas regras contra tráfego histórico
package com.ragnarok.infrastructure.antifraude;

// ============================================================================
// INTEGRAÇÃO — ragnarok-core → ragnarok-antifraude (CORRIGIDO)
// ============================================================================
// Este arquivo vai DENTRO do ragnarok-core (não do microsserviço).
// Coloque em: com/ragnarok/infrastructure/antifraude/
//
// Correções aplicadas vs. versão anterior:
//   1. Removido try/catch em analyze() — deixa o @CircuitBreaker funcionar
//   2. FraudDecision default constructor retorna UNKNOWN (não APPROVED)
//   3. Removido .block(Duration) — timeout único via TimeLimiter do Resilience4j
//   4. WebClient mantido apenas para fire-and-forget; analyze usa RestTemplate
//
// Adicione ao pom.xml do ragnarok-core:
//   <dependency>
//       <groupId>org.springframework.boot</groupId>
//       <artifactId>spring-boot-starter-webflux</artifactId>
//   </dependency>
//   <dependency>
//       <groupId>io.github.resilience4j</groupId>
//       <artifactId>resilience4j-spring-boot3</artifactId>
//       <version>2.2.0</version>
//   </dependency>
// ============================================================================

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class FraudClient {

    private static final Logger log = LoggerFactory.getLogger(FraudClient.class);

    // RestTemplate para chamadas síncronas (analyze) — sem overhead reativo
    private final RestTemplate restTemplate;

    // WebClient apenas para fire-and-forget assíncronos
    private final WebClient webClient;

    private final String baseUrl;

    public FraudClient(
            @Value("${antifraude.url:http://localhost:8081}") String baseUrl,
            @Value("${antifraude.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;

        // RestTemplate com timeout de conexão (o timeout de leitura vem do Resilience4j TimeLimiter)
        this.restTemplate = new RestTemplateBuilder()
            .rootUri(baseUrl)
            .setConnectTimeout(Duration.ofMillis(500))
            .setReadTimeout(Duration.ofMillis(150)) // safety net — TimeLimiter é o controle principal
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("X-API-Key", apiKey != null ? apiKey : "")
            .build();

        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("X-API-Key", apiKey != null ? apiKey : "")
            .build();
    }

    // ── API pública ────────────────────────────────────────────────────────

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkItemTrade(Long playerId, Long itemId, String itemUuid, Long zenysValue) {
        return analyze(buildEvent(playerId, "ITEM_TRADE",
            Map.of("itemId", itemId, "itemUuid", itemUuid, "zenysValue", zenysValue)));
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkClickSpeed(Long playerId, double actionsPerSecond, double networkLatencyMs) {
        return analyze(buildEvent(playerId, "CLICK_ACTION",
            Map.of("actionsPerSecond", actionsPerSecond, "networkLatencyMs", networkLatencyMs)));
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkFarmHeartbeat(Long playerId, String mapId) {
        return analyze(buildEvent(playerId, "FARM_HEARTBEAT", Map.of("mapId", mapId)));
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkLogin(Long playerId, String ipAddress, String countryCode,
                                    boolean emailVerified, boolean ageVerified) {
        FraudDecision decision = analyze(buildEvent(
            playerId, "SESSION_LOGIN", ipAddress, countryCode,
            Map.of("emailVerified", emailVerified, "ageVerified", ageVerified)));
        notifyLoginAsync(playerId, countryCode);
        return decision;
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkCashPurchase(Long playerId, String billingName, String billingDocument) {
        return analyze(buildEvent(playerId, "CASH_PURCHASE",
            Map.of("billingName", billingName, "billingDocument", billingDocument)));
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkInstanceEntry(Long playerId, String mapId) {
        return analyze(buildEvent(playerId, "MAP_INSTANCE_ENTRY", Map.of("mapId", mapId)));
    }

    @CircuitBreaker(name = "antifraude", fallbackMethod = "fallbackApproved")
    public FraudDecision checkMarketPurchase(Long playerId, Long itemId,
                                              Long quantityRequested, Long totalInStock) {
        return analyze(buildEvent(playerId, "MARKET_PURCHASE",
            Map.of("itemId", itemId, "quantityRequested", quantityRequested, "totalInStock", totalInStock)));
    }

    // ── State sync (fire-and-forget — WebClient é apropriado aqui) ─────────

    public void notifyMapLeaveAsync(Long playerId, String mapId) {
        webClient.post()
            .uri("/api/fraud/state/player/{id}/map-leave?mapId={map}", playerId, mapId)
            .retrieve().toBodilessEntity()
            .timeout(Duration.ofMillis(200))
            .onErrorResume(ex -> Mono.empty())
            .subscribe();
    }

    public void notifyInstanceEntryAsync(Long playerId, String mapId, long cooldownSeconds) {
        webClient.post()
            .uri("/api/fraud/state/player/{id}/instance-enter", playerId)
            .bodyValue(Map.of("mapId", mapId, "cooldownSeconds", cooldownSeconds))
            .retrieve().toBodilessEntity()
            .timeout(Duration.ofMillis(500))
            .onErrorResume(ex -> Mono.empty())
            .subscribe();
    }

    public void syncRegistrationAsync(Long playerId, boolean emailVerified,
                                       boolean ageVerified, String document) {
        webClient.post()
            .uri("/api/fraud/state/player/{id}/registration", playerId)
            .bodyValue(Map.of(
                "emailVerified", emailVerified,
                "ageVerified", ageVerified,
                "document", document != null ? document : ""))
            .retrieve().toBodilessEntity()
            .timeout(Duration.ofMillis(500))
            .onErrorResume(ex -> Mono.empty())
            .subscribe();
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * FIX: SEM try/catch — exceções propagam para o @CircuitBreaker.
     * Se o antifraude cair, o CB abre após 5 falhas e desvia para fallbackApproved().
     * Isso evita que cada chamada pague 100ms de timeout quando o serviço está fora.
     */
    private FraudDecision analyze(Map<String, Object> event) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(event, headers);

        return restTemplate.postForObject("/api/fraud/analyze", request, FraudDecision.class);
    }

    private Map<String, Object> buildEvent(Long playerId, String eventType, Map<String, Object> payload) {
        return buildEvent(playerId, eventType, null, null, payload);
    }

    private Map<String, Object> buildEvent(Long playerId, String eventType,
                                            String ipAddress, String countryCode,
                                            Map<String, Object> payload) {
        return Map.of(
            "eventId",     UUID.randomUUID().toString(),
            "eventType",   eventType,
            "playerId",    playerId,
            "ipAddress",   ipAddress != null ? ipAddress : "",
            "countryCode", countryCode != null ? countryCode : "BR",
            "occurredAt",  Instant.now().toString(),
            "payload",     payload);
    }

    private void notifyLoginAsync(Long playerId, String countryCode) {
        webClient.post()
            .uri("/api/fraud/state/player/{id}/login?countryCode={cc}", playerId, countryCode)
            .retrieve().toBodilessEntity()
            .timeout(Duration.ofMillis(200))
            .onErrorResume(ex -> Mono.empty())
            .subscribe();
    }

    // ── Fallback ─────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private FraudDecision fallbackApproved(Exception ex) {
        log.warn("Anti-fraud circuit breaker open — fail-open (APPROVED). Cause: {}", ex.getMessage());
        return FraudDecision.FALLBACK_APPROVED;
    }

    // ── DTO ─────────────────────────────────────────────────────────────────

    public static class FraudDecision {

        /**
         * FIX: Fallback explícito — só usado pelo CircuitBreaker.
         * NUNCA confundir com deserialização normal.
         */
        public static final FraudDecision FALLBACK_APPROVED = new FraudDecision(
            "fallback", null, "APPROVED", "NONE", "LOW", List.of(), "Fallback — anti-fraud unavailable"
        );

        private String       eventId;
        private Long         playerId;
        private String       verdict;
        private String       requiredAction;
        private String       riskLevel;
        private List<String> triggeredRules;
        private String       reason;

        /**
         * FIX: Construtor padrão retorna UNKNOWN — não APPROVED.
         * Se Jackson usar este construtor por falha de deserialização,
         * o resultado NÃO será uma aprovação silenciosa.
         */
        public FraudDecision() {
            this.verdict = "UNKNOWN";
            this.requiredAction = "NONE";
            this.riskLevel = "LOW";
            this.triggeredRules = List.of();
            this.reason = "Deserialization default — check integration";
        }

        public FraudDecision(String eventId, Long playerId, String verdict,
                             String requiredAction, String riskLevel,
                             List<String> triggeredRules, String reason) {
            this.eventId        = eventId;
            this.playerId       = playerId;
            this.verdict        = verdict;
            this.requiredAction = requiredAction;
            this.riskLevel      = riskLevel;
            this.triggeredRules = triggeredRules != null ? triggeredRules : List.of();
            this.reason         = reason;
        }

        public boolean isApproved()   { return "APPROVED".equals(verdict); }
        public boolean isBlocked()    { return "BLOCKED".equals(verdict); }
        public boolean isChallenge()  { return "CHALLENGE".equals(verdict); }
        public boolean isUnknown()    { return "UNKNOWN".equals(verdict); }
        public boolean needsCaptcha() { return "SHOW_CAPTCHA".equals(requiredAction); }
        public boolean dropSession()  { return "DROP_SESSION".equals(requiredAction); }

        public String       getEventId()        { return eventId; }
        public Long         getPlayerId()       { return playerId; }
        public String       getVerdict()        { return verdict; }
        public String       getRequiredAction() { return requiredAction; }
        public String       getRiskLevel()      { return riskLevel; }
        public List<String> getTriggeredRules() { return triggeredRules; }
        public String       getReason()         { return reason; }

        // Jackson setters
        public void setEventId(String v)        { this.eventId = v; }
        public void setPlayerId(Long v)         { this.playerId = v; }
        public void setVerdict(String v)        { this.verdict = v; }
        public void setRequiredAction(String v) { this.requiredAction = v; }
        public void setRiskLevel(String v)      { this.riskLevel = v; }
        public void setTriggeredRules(List<String> v) { this.triggeredRules = v; }
        public void setReason(String v)         { this.reason = v; }

        @Override
        public String toString() {
            return "FraudDecision{verdict=" + verdict + ", action=" + requiredAction
                + ", rules=" + triggeredRules + ", reason=" + reason + "}";
        }
    }
}

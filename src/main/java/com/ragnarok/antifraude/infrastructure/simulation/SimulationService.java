package com.ragnarok.antifraude.infrastructure.simulation;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulation engine that exercises all 9 fraud rules through the live pipeline.
 * Lives in infrastructure because it needs direct Redis access for state seeding.
 *
 * Player IDs start from SIM_PLAYER_BASE (900_000) to avoid collision with real data.
 */
@Service
public class SimulationService {

    private static final long SIM_PLAYER_BASE = 900_000L;

    // Redis key prefixes matching RedisPlayerActivityAdapter constants
    private static final String FARM_KEY_PREFIX     = "antifraude:farm:";
    private static final String LOGIN_KEY_PREFIX    = "antifraude:login:";
    private static final String DOC_KEY_PREFIX      = "antifraude:doc:";
    private static final String INSTANCE_KEY_PREFIX = "antifraude:instance:";

    private final FraudAnalysisUseCase fraudAnalysisUseCase;
    private final RedisTemplate<String, String> redisTemplate;

    public SimulationService(FraudAnalysisUseCase fraudAnalysisUseCase,
                             RedisTemplate<String, String> redisTemplate) {
        this.fraudAnalysisUseCase = fraudAnalysisUseCase;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Runs a named scenario {@code count} times and returns aggregated SimulationResult.
     *
     * @param scenario  name of scenario to run
     * @param count     number of events to generate
     * @return          aggregated results
     * @throws IllegalArgumentException for unknown scenario names
     */
    public SimulationResult simulate(String scenario, int count) {
        long start = System.currentTimeMillis();

        List<FraudDecision> decisions = switch (scenario) {
            case "normal-traffic"        -> runNormalTraffic(count);
            case "item-dupe"             -> runItemDupe(count);
            case "disproportionate-transfer" -> runDisproportionateTransfer(count);
            case "bot-farm"              -> runBotFarm(count);
            case "bot-attack"            -> runBotAttack(count);
            case "unregistered-account"  -> runUnregisteredAccount(count);
            case "cash-fraud"            -> runCashFraud(count);
            case "instance-spam"         -> runInstanceSpam(count);
            case "impossible-travel"     -> runImpossibleTravel(count);
            case "market-monopoly"       -> runMarketMonopoly(count);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };

        long durationMs = System.currentTimeMillis() - start;
        return aggregate(scenario, decisions, durationMs);
    }

    // ── Scenario implementations ──────────────────────────────────────────────

    /** Mixed ITEM_TRADE / CLICK_ACTION / SESSION_LOGIN events — should all be approved. */
    private List<FraudDecision> runNormalTraffic(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        String[] types = {"ITEM_TRADE", "CLICK_ACTION", "SESSION_LOGIN"};
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + i;
            String type = types[i % types.length];
            Map<String, Object> payload = new HashMap<>();
            switch (type) {
                case "ITEM_TRADE" -> {
                    payload.put("itemUuid", UUID.randomUUID().toString());
                    payload.put("itemId", 502L);
                    payload.put("zenysValue", 100L);
                    payload.put("targetPlayerId", playerId + 1);
                }
                case "CLICK_ACTION" -> {
                    payload.put("actionsPerSecond", 3.0);
                    payload.put("sessionDurationMs", 5000L);
                }
                case "SESSION_LOGIN" -> {
                    payload.put("emailVerified", true);
                    payload.put("ageVerified", true);
                }
            }
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), type, playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /** Same UUID sent twice — triggers ANTI_DUPE. */
    private List<FraudDecision> runItemDupe(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 100 + i;
            // Re-use same UUID pair to trigger duplicate detection
            String itemUuid = "dupe-sim-" + i;
            Map<String, Object> payload = Map.of(
                "itemUuid", itemUuid,
                "itemId", 502L,
                "zenysValue", 100L,
                "targetPlayerId", playerId + 1L
            );
            // Send first event
            FraudEvent event1 = buildEvent(UUID.randomUUID().toString(), "ITEM_TRADE", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event1));
            // Send duplicate immediately
            FraudEvent event2 = buildEvent(UUID.randomUUID().toString(), "ITEM_TRADE", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event2));
        }
        return decisions;
    }

    /** Item 501 (Red Herb, ~50z) with zenysValue=50_000_000 — triggers DISPROPORTIONATE_TRANSFER. */
    private List<FraudDecision> runDisproportionateTransfer(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 200 + i;
            Map<String, Object> payload = Map.of(
                "itemUuid", UUID.randomUUID().toString(),
                "itemId", 501L,
                "zenysValue", 50_000_000L,
                "targetPlayerId", playerId + 1L
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "ITEM_TRADE", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /**
     * Pre-seeds Redis farm session 7 hours ago, then sends FARM_HEARTBEAT.
     * Key: antifraude:farm:{playerId}:{mapId} = ISO-8601 start timestamp
     */
    private List<FraudDecision> runBotFarm(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        String mapId = "SIM_FARM_MAP";
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 300 + i;
            // Seed: session started 7 hours ago (exceeds 6h CAPTCHA threshold)
            String farmKey = FARM_KEY_PREFIX + playerId + ":" + mapId;
            String sessionStart = Instant.now().minus(Duration.ofHours(7)).toString();
            redisTemplate.opsForValue().set(farmKey, sessionStart, Duration.ofHours(48));

            Map<String, Object> payload = Map.of("mapId", mapId);
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "FARM_HEARTBEAT", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /** CLICK_ACTION with actionsPerSecond=30.0 — triggers BOT_CLICK_SPEED. */
    private List<FraudDecision> runBotAttack(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 400 + i;
            Map<String, Object> payload = Map.of(
                "actionsPerSecond", 30.0,
                "sessionDurationMs", 60_000L
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "CLICK_ACTION", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /** SESSION_LOGIN with emailVerified=false — triggers REGISTRATION_VALIDATION. */
    private List<FraudDecision> runUnregisteredAccount(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 500 + i;
            Map<String, Object> payload = Map.of(
                "emailVerified", false,
                "ageVerified", true
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "SESSION_LOGIN", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /**
     * Pre-seeds registered document in Redis, then sends CASH_PURCHASE with different document.
     * Key: antifraude:doc:{playerId} = document string
     * Triggers CASH_SECURITY.
     */
    private List<FraudDecision> runCashFraud(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 600 + i;
            // Seed registered document
            String docKey = DOC_KEY_PREFIX + playerId;
            redisTemplate.opsForValue().set(docKey, "12345678901", Duration.ofDays(365));

            // Send cash purchase with different document
            Map<String, Object> payload = Map.of(
                "billingDocument", "99988877766",
                "billingName", "Sim Player",
                "amountCents", 1000L
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "CASH_PURCHASE", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /**
     * Pre-seeds instance cooldown in Redis (active for 10 more minutes), then sends MAP_INSTANCE_ENTRY.
     * Key: antifraude:instance:{playerId}:{mapId} = ISO-8601 expiry timestamp
     * Triggers INSTANCE_COOLDOWN.
     */
    private List<FraudDecision> runInstanceSpam(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        String mapId = "SIM_MVP_ROOM";
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 700 + i;
            // Seed: cooldown expires in 10 minutes
            String instanceKey = INSTANCE_KEY_PREFIX + playerId + ":" + mapId;
            String expiry = Instant.now().plus(Duration.ofMinutes(10)).toString();
            redisTemplate.opsForValue().set(instanceKey, expiry, Duration.ofMinutes(11));

            Map<String, Object> payload = Map.of("mapId", mapId);
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "MAP_INSTANCE_ENTRY", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /**
     * Pre-seeds last login as BR 30 minutes ago, then sends SESSION_LOGIN from JP.
     * Key: antifraude:login:{playerId} hash with fields "country" and "timestamp"
     * Triggers IMPOSSIBLE_TRAVEL (country changed in < 2 hours).
     */
    private List<FraudDecision> runImpossibleTravel(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 800 + i;
            // Seed: last login was from BR 30 minutes ago
            String loginKey = LOGIN_KEY_PREFIX + playerId;
            redisTemplate.opsForHash().put(loginKey, "country", "BR");
            redisTemplate.opsForHash().put(loginKey, "timestamp",
                Instant.now().minus(Duration.ofMinutes(30)).toString());
            redisTemplate.expire(loginKey, Duration.ofDays(30));

            // New login from Japan — triggers impossible travel
            Map<String, Object> payload = Map.of(
                "emailVerified", true,
                "ageVerified", true
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "SESSION_LOGIN", playerId, "JP", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    /** MARKET_PURCHASE with quantityRequested=950, totalInStock=1000 — triggers MARKET_MONOPOLY. */
    private List<FraudDecision> runMarketMonopoly(int count) {
        List<FraudDecision> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 900 + i;
            Map<String, Object> payload = Map.of(
                "itemId", 501L,
                "quantityRequested", 950L,
                "totalInStock", 1000L,
                "zenysValue", 1000L
            );
            FraudEvent event = buildEvent(UUID.randomUUID().toString(), "MARKET_PURCHASE", playerId, "BR", payload);
            decisions.add(fraudAnalysisUseCase.analyze(event));
        }
        return decisions;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FraudEvent buildEvent(String eventId, String eventType, Long playerId,
                                  String countryCode, Map<String, Object> payload) {
        return new FraudEvent(
            eventId,
            eventType,
            playerId,
            "127.0.0.1",
            countryCode,
            Instant.now(),
            payload
        );
    }

    private SimulationResult aggregate(String scenario, List<FraudDecision> decisions, long durationMs) {
        Map<String, Long> verdictCounts = new HashMap<>();
        Map<String, Long> triggeredRules = new HashMap<>();

        for (FraudDecision d : decisions) {
            verdictCounts.merge(d.verdict().name(), 1L, Long::sum);
            for (String rule : d.triggeredRules()) {
                triggeredRules.merge(rule, 1L, Long::sum);
            }
        }

        return new SimulationResult(scenario, decisions.size(), verdictCounts, triggeredRules, durationMs);
    }
}

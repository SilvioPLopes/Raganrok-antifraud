package com.ragnarok.antifraude.infrastructure.adapter.out.redis;

import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementação Redis dos dados quentes de PlayerActivityRepository.
 * Farm sessions, click timing, login country, registration data.
 *
 * Instance cooldown (Regra 7) é delegado ao JPA adapter — dados duráveis.
 *
 * TODO [Claude Code]: Implementar recordClickAndGetStdDev com sliding window.
 *       Sugestão: usar Redis ZADD com timestamp como score, ZRANGEBYSCORE
 *       para janela de 10 segundos, calcular stddev dos intervalos.
 */
@Component
public class RedisPlayerActivityAdapter implements PlayerActivityRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisPlayerActivityAdapter.class);

    private static final String FARM_KEY_PREFIX = "antifraude:farm:";
    private static final String CLICK_KEY_PREFIX = "antifraude:clicks:";
    private static final String LOGIN_KEY_PREFIX = "antifraude:login:";
    private static final String DOC_KEY_PREFIX = "antifraude:doc:";
    private static final String INSTANCE_KEY_PREFIX = "antifraude:instance:";

    private final StringRedisTemplate redis;

    public RedisPlayerActivityAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ── Farm session (Regra 3) ──────────────────────────────────────────────

    @Override
    public Optional<Duration> getFarmDuration(Long playerId, String mapId) {
        String key = FARM_KEY_PREFIX + playerId + ":" + mapId;
        String startStr = redis.opsForValue().get(key);
        if (startStr == null) return Optional.empty();
        try {
            Instant start = Instant.parse(startStr);
            return Optional.of(Duration.between(start, Instant.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateFarmHeartbeat(Long playerId, String mapId) {
        String key = FARM_KEY_PREFIX + playerId + ":" + mapId;
        // Só seta se não existe — preserva o timestamp de início da sessão
        redis.opsForValue().setIfAbsent(key, Instant.now().toString(), Duration.ofHours(48));
    }

    @Override
    public void resetFarmSession(Long playerId, String mapId) {
        String key = FARM_KEY_PREFIX + playerId + ":" + mapId;
        redis.delete(key);
    }

    // ── Click timing (Regra 4) ──────────────────────────────────────────────

    @Override
    public double recordClickAndGetStdDev(Long playerId, long timestampMs) {
        // TODO [Claude Code]: Implementar sliding window com ZADD/ZRANGEBYSCORE
        // Por enquanto retorna um stddev alto (humano) para não bloquear ninguém
        String key = CLICK_KEY_PREFIX + playerId;
        redis.opsForZSet().add(key, String.valueOf(timestampMs), timestampMs);
        redis.expire(key, Duration.ofSeconds(30)); // janela de 30s

        // Busca últimos clicks na janela
        long windowStart = timestampMs - 10_000; // 10 segundos
        var entries = redis.opsForZSet().rangeByScore(key, windowStart, timestampMs);
        if (entries == null || entries.size() < 3) return 999.0; // poucos dados → humano

        // Calcula stddev dos intervalos
        List<Long> timestamps = entries.stream()
            .map(Long::parseLong)
            .sorted()
            .toList();

        List<Long> intervals = new java.util.ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
            .mapToDouble(i -> Math.pow(i - mean, 2))
            .average().orElse(0);

        return Math.sqrt(variance);
    }

    // ── Login / Country (Regra 8) ───────────────────────────────────────────

    @Override
    public Optional<LoginRecord> getLastLogin(Long playerId) {
        String key = LOGIN_KEY_PREFIX + playerId;
        String country = (String) redis.opsForHash().get(key, "country");
        String timeStr = (String) redis.opsForHash().get(key, "timestamp");
        if (country == null || timeStr == null) return Optional.empty();
        try {
            return Optional.of(new LoginRecord(country, Instant.parse(timeStr)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateLoginCountry(Long playerId, String countryCode) {
        String key = LOGIN_KEY_PREFIX + playerId;
        redis.opsForHash().put(key, "country", countryCode);
        redis.opsForHash().put(key, "timestamp", Instant.now().toString());
        redis.expire(key, Duration.ofDays(30));
    }

    // ── Instance cooldown (Regra 7) — Redis como cache, PostgreSQL como source of truth ─

    @Override
    public Optional<Instant> getInstanceCooldownExpiry(Long playerId, String mapId) {
        // Tenta Redis primeiro (cache)
        String key = INSTANCE_KEY_PREFIX + playerId + ":" + mapId;
        String expiryStr = redis.opsForValue().get(key);
        if (expiryStr != null) {
            try {
                return Optional.of(Instant.parse(expiryStr));
            } catch (Exception ignored) {}
        }
        // TODO [Claude Code]: Fallback para JPA adapter se cache miss
        return Optional.empty();
    }

    @Override
    public void recordInstanceEntry(Long playerId, String mapId, Duration cooldown) {
        Instant expiry = Instant.now().plus(cooldown);
        String key = INSTANCE_KEY_PREFIX + playerId + ":" + mapId;
        redis.opsForValue().set(key, expiry.toString(), cooldown.plusHours(1)); // TTL ligeiramente maior que cooldown
        // TODO [Claude Code]: Persistir também no PostgreSQL (source of truth)
    }

    // ── Registration data (Regras 5, 6) ─────────────────────────────────────

    @Override
    public Optional<String> getPlayerDocument(Long playerId) {
        String key = DOC_KEY_PREFIX + playerId;
        String doc = redis.opsForValue().get(key);
        return Optional.ofNullable(doc).filter(s -> !s.isBlank());
    }

    @Override
    public void saveRegistrationData(Long playerId, boolean emailVerified, boolean ageVerified, String document) {
        if (document != null && !document.isBlank()) {
            redis.opsForValue().set(DOC_KEY_PREFIX + playerId, document, Duration.ofDays(365));
        }
        // TODO [Claude Code]: Salvar emailVerified/ageVerified se necessário para cache local
    }
}

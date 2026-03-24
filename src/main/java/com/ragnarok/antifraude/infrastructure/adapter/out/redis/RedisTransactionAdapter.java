package com.ragnarok.antifraude.infrastructure.adapter.out.redis;

import com.ragnarok.antifraude.domain.port.out.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Implementação Redis da port TransactionRepository.
 * Operações sub-millisecond para anti-dupe e market prices.
 */
@Component
public class RedisTransactionAdapter implements TransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisTransactionAdapter.class);
    private static final String DUPE_KEY_PREFIX = "antifraude:dupe:";
    private static final String MARKET_PRICE_KEY_PREFIX = "antifraude:market:price:";

    private final StringRedisTemplate redis;

    public RedisTransactionAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquireDupeLock(String itemUuid, long ttlMillis) {
        String key = DUPE_KEY_PREFIX + itemUuid;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public Optional<Long> getMarketPrice(Long itemId) {
        String key = MARKET_PRICE_KEY_PREFIX + itemId;
        String value = redis.opsForValue().get(key);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            log.warn("Invalid market price in Redis for item {}: {}", itemId, value);
            return Optional.empty();
        }
    }
}

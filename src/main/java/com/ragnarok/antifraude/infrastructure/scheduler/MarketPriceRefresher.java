package com.ragnarok.antifraude.infrastructure.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Job agendado: lê preços medianos do PostgreSQL e empurra para o Redis.
 * A Regra 2 lê Redis em < 1ms; este job é o único que paga o custo do SQL.
 * Roda a cada 5 minutos.
 */
@Component
public class MarketPriceRefresher {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceRefresher.class);
    private static final String MARKET_PRICE_KEY_PREFIX = "antifraude:market:price:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public MarketPriceRefresher(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 5_000) // 5 min, startup delay 5s
    public void refreshPrices() {
        try {
            var prices = jdbc.queryForList(
                "SELECT item_id, median_price FROM market_prices WHERE median_price > 0");

            int count = 0;
            for (var row : prices) {
                Long itemId = ((Number) row.get("item_id")).longValue();
                Long price = ((Number) row.get("median_price")).longValue();
                redis.opsForValue().set(MARKET_PRICE_KEY_PREFIX + itemId, price.toString(), Duration.ofMinutes(10));
                count++;
            }

            log.info("Market prices refreshed: {} items synced to Redis", count);
        } catch (Exception e) {
            log.error("Failed to refresh market prices: {}", e.getMessage());
            // Fail silently — stale prices em Redis são melhores que nenhum preço
        }
    }
}

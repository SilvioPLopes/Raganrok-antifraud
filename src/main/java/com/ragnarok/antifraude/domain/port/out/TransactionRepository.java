package com.ragnarok.antifraude.domain.port.out;

import java.util.Optional;

/**
 * Port de saída — acesso a dados de transações.
 * Implementado por RedisTransactionAdapter (infrastructure).
 *
 * Interface pura Java — nenhum import de Spring/Redis aqui.
 */
public interface TransactionRepository {

    /**
     * Tenta adquirir lock exclusivo para um item UUID (anti-dupe).
     * @return true se o lock foi adquirido (primeira transação), false se já existe (dupe).
     */
    boolean tryAcquireDupeLock(String itemUuid, long ttlMillis);

    /**
     * Busca o preço de mercado mediano de um item.
     * @return preço em zenys, ou empty se não cadastrado.
     */
    Optional<Long> getMarketPrice(Long itemId);
}

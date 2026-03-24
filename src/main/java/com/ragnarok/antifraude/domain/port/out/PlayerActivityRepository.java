package com.ragnarok.antifraude.domain.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Port de saída — acesso a dados de atividade do jogador.
 * Implementado por RedisPlayerActivityAdapter + JPA adapter (infrastructure).
 */
public interface PlayerActivityRepository {

    // ── Farm session (Regra 3) ──────────────────────────────────────────────

    /** Retorna há quanto tempo o jogador está farmando no mapa atual. */
    Optional<Duration> getFarmDuration(Long playerId, String mapId);

    /** Registra/atualiza heartbeat de farm. */
    void updateFarmHeartbeat(Long playerId, String mapId);

    /** Reseta sessão de farm (ex: jogador saiu do mapa). */
    void resetFarmSession(Long playerId, String mapId);

    // ── Click timing (Regra 4) ──────────────────────────────────────────────

    /** Registra timestamp de clique e retorna o stddev da janela recente. */
    double recordClickAndGetStdDev(Long playerId, long timestampMs);

    // ── Login / Country (Regra 8) ───────────────────────────────────────────

    /** Retorna o último país e timestamp de login. */
    Optional<LoginRecord> getLastLogin(Long playerId);

    /** Atualiza país de login. */
    void updateLoginCountry(Long playerId, String countryCode);

    record LoginRecord(String countryCode, Instant timestamp) {}

    // ── Instance cooldown (Regra 7) ─────────────────────────────────────────

    /** Retorna o instante em que o cooldown expira, ou empty se não há cooldown ativo. */
    Optional<Instant> getInstanceCooldownExpiry(Long playerId, String mapId);

    /** Registra entrada em instância com cooldown. */
    void recordInstanceEntry(Long playerId, String mapId, Duration cooldown);

    // ── Registration data (Regras 5, 6) ─────────────────────────────────────

    /** Busca documento (CPF/CNPJ) do jogador. */
    Optional<String> getPlayerDocument(Long playerId);

    /** Salva dados de registro. */
    void saveRegistrationData(Long playerId, boolean emailVerified, boolean ageVerified, String document);
}

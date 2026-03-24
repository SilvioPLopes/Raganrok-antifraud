package com.ragnarok.infrastructure.antifraude;

// ============================================================================
// EXEMPLOS DE USO — FraudClient dentro do ragnarok-core
// ============================================================================
// Mostra como injetar e usar o FraudClient nos serviços existentes
// do ragnarok-core sem modificar a lógica de negócio do domínio.
//
// Padrão: fraud check ANTES do commit, throw FraudBlockedException se bloqueado.
// ragnarok-core trata FraudBlockedException como qualquer exceção de domínio.
// ============================================================================

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EXEMPLO 1 — BattleService modificado para checar bot por velocidade de clique
 *
 * No seu BattleService.realizarAtaque(), adicione ANTES do processamento:
 *
 * <pre>
 * {@code
 * @Service
 * public class BattleService {
 *
 *     private final FraudClient fraudClient; // injeta via construtor
 *
 *     public BattleResult realizarAtaque(Long playerId, Long monsterId) {
 *
 *         // ── Fraud check: velocidade de clique ────────────────────────
 *         double aps       = metricsService.getActionsPerSecond(playerId);
 *         double latencyMs = metricsService.getNetworkLatencyMs(playerId);
 *
 *         FraudClient.FraudDecision fraud = fraudClient.checkClickSpeed(playerId, aps, latencyMs);
 *
 *         if (fraud.isBlocked()) {
 *             if (fraud.needsCaptcha()) {
 *                 throw new CaptchaRequiredException(fraud.getReason()); // mostra CAPTCHA
 *             }
 *             throw new FraudBlockedException(fraud.getReason()); // suspende conta
 *         }
 *
 *         // ── Lógica original do BattleService (inalterada) ───────────
 *         Player  player  = playerRepo.findById(playerId).orElseThrow(...);
 *         Monster monster = monsterRepo.findById(monsterId).orElseThrow(...);
 *         // ... combate normal
 *     }
 * }
 * }
 * </pre>
 */
@SuppressWarnings("unused")
class BattleServiceFraudExample {

    private static final Logger log = LoggerFactory.getLogger(BattleServiceFraudExample.class);

    /**
     * EXEMPLO 2 — ItemService.realizarTroca() com anti-dupe + transfer check
     *
     * <pre>
     * {@code
     * public TradeResult realizarTroca(Long fromPlayerId, Long toPlayerId,
     *                                   Long itemId, String itemUuid, Long zenysValue) {
     *
     *     // Regra 1: Anti-Dupe (verifica se o item UUID está em trade ativo)
     *     FraudClient.FraudDecision dupeCheck =
     *         fraudClient.checkItemTrade(fromPlayerId, itemId, itemUuid, zenysValue);
     *
     *     if (dupeCheck.isBlocked()) {
     *         log.warn("Trade bloqueado por [{}]: {}", dupeCheck.getTriggeredRules(), dupeCheck.getReason());
     *         throw new FraudBlockedException(dupeCheck.getReason());
     *     }
     *
     *     // Regra 2: Transferência desproporcional (checa ratio zeny/preço de mercado)
     *     // (já incluso no checkItemTrade — ambas as regras correm em paralelo)
     *
     *     // Commit da troca só acontece aqui se ambas as regras aprovaram
     *     return tradeRepository.commit(fromPlayerId, toPlayerId, itemId, itemUuid, zenysValue);
     * }
     * }
     * </pre>
     */
    void itemServiceExample() {}

    /**
     * EXEMPLO 3 — MapService.travel() com cooldown de instâncias
     *
     * <pre>
     * {@code
     * public TravelResult travel(Long playerId, String destinationMapId) {
     *
     *     boolean isInstanceMap = instanceMapIds.contains(destinationMapId);
     *
     *     if (isInstanceMap) {
     *         FraudClient.FraudDecision check =
     *             fraudClient.checkInstanceEntry(playerId, destinationMapId);
     *
     *         if (check.isBlocked()) {
     *             throw new InstanceCooldownException(check.getReason()); // ex: "Cooldown: 23h 45m"
     *         }
     *
     *         // Notifica o antifraude para registrar a entrada e resetar o cooldown
     *         long cooldownSeconds = instanceMapCooldowns.get(destinationMapId); // ex: 604800 (7 dias)
     *         fraudClient.notifyInstanceEntryAsync(playerId, destinationMapId, cooldownSeconds);
     *     }
     *
     *     return mapRepository.teleport(playerId, destinationMapId);
     * }
     * }
     * </pre>
     */
    void mapServiceExample() {}

    /**
     * EXEMPLO 4 — LoginService com impossible travel check
     *
     * <pre>
     * {@code
     * public LoginResult login(String username, String password, String ipAddress) {
     *
     *     Player player = authenticate(username, password); // autenticação normal
     *
     *     String countryCode   = geoIpService.getCountry(ipAddress); // resolve país do IP
     *     boolean emailVerified = player.isEmailVerified();
     *     boolean ageVerified   = player.isAgeVerified();
     *
     *     FraudClient.FraudDecision fraud =
     *         fraudClient.checkLogin(player.getId(), ipAddress, countryCode,
     *                               emailVerified, ageVerified);
     *
     *     if (fraud.dropSession()) {
     *         // Viagem impossível — derruba a sessão imediatamente
     *         sessionService.invalidateAll(player.getId());
     *         throw new ImpossibleTravelException(fraud.getReason());
     *     }
     *
     *     if (fraud.isBlocked()) {
     *         // Email não verificado, menor de idade, etc.
     *         throw new LoginBlockedException(fraud.getReason());
     *     }
     *
     *     return sessionService.create(player);
     * }
     * }
     * </pre>
     */
    void loginServiceExample() {}

    /**
     * EXEMPLO 5 — Exceções personalizadas para o ragnarok-core lidar
     */
    static class FraudBlockedException extends RuntimeException {
        public FraudBlockedException(String reason) { super("Blocked by anti-fraud: " + reason); }
    }

    static class CaptchaRequiredException extends RuntimeException {
        public CaptchaRequiredException(String reason) { super("CAPTCHA required: " + reason); }
    }

    static class InstanceCooldownException extends RuntimeException {
        public InstanceCooldownException(String reason) { super(reason); }
    }

    static class ImpossibleTravelException extends RuntimeException {
        public ImpossibleTravelException(String reason) { super(reason); }
    }

    static class LoginBlockedException extends RuntimeException {
        public LoginBlockedException(String reason) { super(reason); }
    }
}

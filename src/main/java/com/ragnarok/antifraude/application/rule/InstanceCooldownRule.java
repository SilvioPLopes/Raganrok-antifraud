package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Regra 7 — Trava de instâncias.
 * Impede reentrada em mapas especiais se o cooldown ainda não expirou.
 * Cooldowns são de dias/semanas (ex: 7 dias para MVP rooms).
 * Fonte da verdade: PostgreSQL (indexed) — dados duráveis que sobrevivem a restart.
 */
@Component
public class InstanceCooldownRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(InstanceCooldownRule.class);

    private final PlayerActivityRepository playerRepo;

    public InstanceCooldownRule(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override public String ruleId()         { return "INSTANCE_COOLDOWN"; }
    @Override public Set<String> eventTypes() { return Set.of("MAP_INSTANCE_ENTRY"); }
    @Override public int priority()          { return 7; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            String mapId = event.payloadValue("mapId", String.class);
            if (mapId == null) return RuleResult.approved(ruleId());

            var expiry = playerRepo.getInstanceCooldownExpiry(event.playerId(), mapId);
            if (expiry.isEmpty()) {
                return RuleResult.approved(ruleId()); // Sem cooldown ativo
            }

            Instant now = Instant.now();
            if (now.isBefore(expiry.get())) {
                Duration remaining = Duration.between(now, expiry.get());
                long hours = remaining.toHours();
                long minutes = remaining.toMinutesPart();

                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.MEDIUM,
                    String.format("Instance cooldown active for map %s — %dh %dm remaining", mapId, hours, minutes));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}

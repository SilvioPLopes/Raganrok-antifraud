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
import java.util.Set;

/**
 * Regra 3 — Detector de bot por tempo.
 * Farm contínuo no mesmo mapa:
 *   >6h  → SHOW_CAPTCHA
 *   >12h → FLAG_FOR_REVIEW
 *   >24h → DROP_SESSION
 */
@Component
public class BotFarmTimeRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(BotFarmTimeRule.class);
    private static final long CAPTCHA_HOURS = 6;
    private static final long FLAG_HOURS = 12;
    private static final long DROP_HOURS = 24;

    private final PlayerActivityRepository playerRepo;

    public BotFarmTimeRule(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override public String ruleId()         { return "BOT_FARM_TIME"; }
    @Override public Set<String> eventTypes() { return Set.of("FARM_HEARTBEAT"); }
    @Override public int priority()          { return 3; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            String mapId = event.payloadValue("mapId", String.class);
            if (mapId == null) return RuleResult.approved(ruleId());

            playerRepo.updateFarmHeartbeat(event.playerId(), mapId);

            var duration = playerRepo.getFarmDuration(event.playerId(), mapId);
            if (duration.isEmpty()) return RuleResult.approved(ruleId());

            long hours = duration.get().toHours();

            if (hours >= DROP_HOURS) {
                return RuleResult.blocked(ruleId(), RequiredAction.DROP_SESSION, RiskLevel.CRITICAL,
                    String.format("Continuous farm for %dh on map %s", hours, mapId));
            }
            if (hours >= FLAG_HOURS) {
                return RuleResult.challenge(ruleId(), RequiredAction.FLAG_FOR_REVIEW, RiskLevel.HIGH,
                    String.format("Continuous farm for %dh on map %s", hours, mapId));
            }
            if (hours >= CAPTCHA_HOURS) {
                return RuleResult.challenge(ruleId(), RequiredAction.SHOW_CAPTCHA, RiskLevel.MEDIUM,
                    String.format("Continuous farm for %dh on map %s — captcha required", hours, mapId));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}

package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.port.out.TransactionRepository;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Regra 1 — Anti-Dupe.
 * Bloqueia clonagem de itens usando Redis SET NX atômico no itemUuid.
 * Se o mesmo itemUuid for visto duas vezes em 500ms → BLOCKED.
 */
@Component
public class AntiDupeRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AntiDupeRule.class);
    private static final long DUPE_WINDOW_MS = 500;

    private final TransactionRepository transactionRepo;

    public AntiDupeRule(TransactionRepository transactionRepo) {
        this.transactionRepo = transactionRepo;
    }

    @Override public String ruleId()         { return "ANTI_DUPE"; }
    @Override public Set<String> eventTypes() { return Set.of("ITEM_TRADE"); }
    @Override public int priority()          { return 1; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            String itemUuid = event.payloadValue("itemUuid", String.class);
            if (itemUuid == null || itemUuid.isBlank()) {
                return RuleResult.approved(ruleId());
            }

            boolean acquired = transactionRepo.tryAcquireDupeLock(itemUuid, DUPE_WINDOW_MS);
            if (!acquired) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.CRITICAL,
                    "Duplicate item trade detected: " + itemUuid);
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}

package com.ragnarok.antifraude.domain.model;

/** Resultado final da análise. */
public enum Verdict {
    APPROVED,
    BLOCKED,
    CHALLENGE;

    /** BLOCKED > CHALLENGE > APPROVED — pior veredicto vence. */
    public boolean isWorseThan(Verdict other) {
        return this.ordinal() > other.ordinal();
    }
}

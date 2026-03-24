package com.ragnarok.antifraude.domain.model;

/** Ação que o ragnarok-core deve tomar. */
public enum RequiredAction {
    NONE,
    CANCEL_ACTION,
    SHOW_CAPTCHA,
    DROP_SESSION,
    FLAG_FOR_REVIEW,
    ALERT_ONLY
}

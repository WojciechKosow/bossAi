package com.BossAi.bossAi.entity;

/**
 * Lifecycle of a single podcast clip within a generation.
 *
 * PENDING → RENDERING → READY, or → FAILED on any error.
 * The parent {@link Generation} is not DONE until every clip is terminal.
 */
public enum ClipStatus {
    PENDING,
    RENDERING,
    READY,
    FAILED
}

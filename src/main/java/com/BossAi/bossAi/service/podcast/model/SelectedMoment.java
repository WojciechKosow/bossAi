package com.BossAi.bossAi.service.podcast.model;

/**
 * A candidate moment picked by the LLM director from the diarized transcript.
 *
 * <p>The timestamps are APPROXIMATE — the director eyeballs them from the turn
 * structure. They are made exact afterwards, deterministically, by
 * {@link com.BossAi.bossAi.service.podcast.SentenceBoundarySnapper}. Never treat
 * these as final cut points.
 *
 * @param approxStartMs  rough start in the source episode, in milliseconds
 * @param approxEndMs    rough end in the source episode, in milliseconds
 * @param title          short headline for the clip
 * @param reasoning      why this moment stands alone / would perform well
 */
public record SelectedMoment(int approxStartMs, int approxEndMs, String title, String reasoning) {
}

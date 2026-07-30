package com.BossAi.bossAi.service.music;

import com.BossAi.bossAi.service.generation.context.ScriptResult;

import java.util.List;

/**
 * Result of aligning the music to the video.
 *
 * @param startOffsetMs offset in ms from the start of the music track — from this point
 *                       the music should start playing in the video (seek in FFmpeg).
 *                       E.g. 43000 = start from the 43rd second of the music.
 * @param directions    dynamic per-scene musicDirections —
 *                       based on analysis of the music structure, not GPT guesswork.
 */
public record MusicAlignment(
        int startOffsetMs,
        List<ScriptResult.MusicDirection> directions
) {}

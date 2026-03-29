package com.BossAi.bossAi.service.music;

import com.BossAi.bossAi.service.generation.context.ScriptResult;

import java.util.List;

/**
 * Wynik wyrównania muzyki do wideo.
 *
 * @param startOffsetMs offset w ms od początku utworu muzycznego — od tego momentu
 *                       muzyka powinna zacząć grać w filmie (seek w FFmpeg).
 *                       Np. 43000 = zacznij od 43. sekundy muzyki.
 * @param directions    dynamiczne musicDirections per scena —
 *                       bazowane na analizie struktury muzyki, nie na domyśle GPT.
 */
public record MusicAlignment(
        int startOffsetMs,
        List<ScriptResult.MusicDirection> directions
) {}

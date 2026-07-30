package com.BossAi.bossAi.service.BeatDetection;

import com.BossAi.bossAi.service.generation.GenerationContext;

import java.util.List;

public interface BeatDetectionService {
    List<Integer> detectBeats(String audioPath);

    /**
     * Detects beats and caches the raw AudioAnalysisResponse in the context,
     * so MusicAnalysisService doesn't have to call Python again.
     */
    default List<Integer> detectBeats(String audioPath, GenerationContext context) {
        return detectBeats(audioPath);
    }
}

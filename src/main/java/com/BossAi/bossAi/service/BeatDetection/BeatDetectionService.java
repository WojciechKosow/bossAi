package com.BossAi.bossAi.service.BeatDetection;

import com.BossAi.bossAi.service.generation.GenerationContext;

import java.util.List;

public interface BeatDetectionService {
    List<Integer> detectBeats(String audioPath);

    /**
     * Wykrywa beaty i cachuje raw AudioAnalysisResponse w kontekscie,
     * zeby MusicAnalysisService nie musial wolac Pythona ponownie.
     */
    default List<Integer> detectBeats(String audioPath, GenerationContext context) {
        return detectBeats(audioPath);
    }
}

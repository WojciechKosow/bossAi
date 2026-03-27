package com.BossAi.bossAi.service.BeatDetection;

import java.util.List;

public interface BeatDetectionService {
    List<Integer> detectBeats(String audioPath);
}

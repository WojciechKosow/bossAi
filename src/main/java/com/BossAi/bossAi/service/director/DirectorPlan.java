package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DirectorPlan {

    private VideoStyle style;

    private String pacing;
    private String energyLevel;

    private List<Cut> cuts;

    private List<SceneDirection> scenes;
}

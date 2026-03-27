package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.VideoStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectorPlan {

    private VideoStyle style;

    private String pacing;
    private String energyLevel;

    private List<Cut> cuts;

    private List<SceneDirection> scenes;
}

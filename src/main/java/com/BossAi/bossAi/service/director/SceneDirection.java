package com.BossAi.bossAi.service.director;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SceneDirection {

    private int sceneIndex;

    private List<Cut> cuts;

    private String transitionToNext;
}

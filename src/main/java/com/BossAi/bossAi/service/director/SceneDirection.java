package com.BossAi.bossAi.service.director;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneDirection {

    private int sceneIndex;

    private List<Cut> cuts;

    private String transitionToNext;
}

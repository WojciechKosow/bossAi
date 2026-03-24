package com.BossAi.bossAi.service.style;

import com.BossAi.bossAi.entity.VideoStyle;

public interface StyleService {

    StyleConfig getConfig(VideoStyle style);

    StyleConfig viral();

    StyleConfig highConverting();

    StyleConfig ugc();

    StyleConfig luxury();

    StyleConfig cinematic();

    StyleConfig story();

    StyleConfig product();

    StyleConfig educational();
}

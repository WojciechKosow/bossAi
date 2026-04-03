package com.BossAi.bossAi;

import com.BossAi.bossAi.config.properties.AudioAnalysisProperties;
import com.BossAi.bossAi.config.properties.FalAiProperties;
import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.config.properties.OpenAiProperties;
import com.BossAi.bossAi.config.properties.RemotionRendererProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
		OpenAiProperties.class,
		FalAiProperties.class,
		FfmpegProperties.class,
		AudioAnalysisProperties.class,
		RemotionRendererProperties.class
})
public class BossAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(BossAiApplication.class, args);
	}

}

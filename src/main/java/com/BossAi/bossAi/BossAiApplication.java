package com.BossAi.bossAi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class BossAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(BossAiApplication.class, args);
	}

}

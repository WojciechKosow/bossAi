package com.BossAi.bossAi.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typo-safe binding właściwości FFmpeg z application.properties.
 * Prefix: ffmpeg
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ffmpeg")
public class FfmpegProperties {

    private Binary binary = new Binary();
    private Binary probe  = new Binary();
    private Output output = new Output();
    private Temp   temp   = new Temp();

    @Getter @Setter
    public static class Binary {
        private String path = "/usr/bin/ffmpeg";
    }

    @Getter @Setter
    public static class Output {
        private int    width      = 1080;
        private int    height     = 1920;
        private int    fps        = 30;
        private String videoCodec = "libx264";
        private String audioCodec = "aac";
        private int    crf        = 23;
    }

    @Getter @Setter
    public static class Temp {
        private String dir = "/tmp/bossai/render";
    }
}
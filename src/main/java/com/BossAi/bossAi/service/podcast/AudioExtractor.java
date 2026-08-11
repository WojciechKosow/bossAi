package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Extracts a mono 16 kHz WAV audio track from a source episode (video or audio)
 * using ffmpeg. 16 kHz mono is what WhisperX wants, and stripping the video up
 * front keeps the payload sent to the audio-analysis service small even for a
 * multi-hour episode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioExtractor {

    private final FfmpegProperties ffmpegProperties;

    /**
     * @param sourcePath path to the downloaded source file (mp4, mov, mp3, …)
     * @param workDir    directory to write the extracted wav into
     * @return path to the extracted {@code episode.wav}
     */
    public Path extractWav(Path sourcePath, Path workDir) {
        try {
            Files.createDirectories(workDir);
            Path out = workDir.resolve("episode.wav");

            List<String> cmd = List.of(
                    ffmpegProperties.getBinary().getPath(),
                    "-y",
                    "-i", sourcePath.toString(),
                    "-vn",                 // drop video
                    "-ac", "1",            // mono
                    "-ar", "16000",        // 16 kHz
                    "-c:a", "pcm_s16le",   // uncompressed PCM WAV
                    out.toString()
            );

            log.info("[AudioExtractor] Extracting audio — src: {} → {}", sourcePath, out);
            runFfmpeg(cmd);

            if (!Files.exists(out) || Files.size(out) == 0) {
                throw new IllegalStateException("ffmpeg produced no audio for " + sourcePath);
            }
            log.info("[AudioExtractor] Extracted {} bytes of audio", Files.size(out));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Audio extraction failed for " + sourcePath, e);
        }
    }

    private void runFfmpeg(List<String> cmd) throws Exception {
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        // Drain output so the process can't block on a full pipe buffer.
        StringBuilder tail = new StringBuilder();
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.length() < 4000) {
                    tail.append(line).append('\n');
                }
            }
        }

        boolean finished = process.waitFor(2, TimeUnit.HOURS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out during audio extraction");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg exited " + process.exitValue()
                    + ":\n" + tail);
        }
    }

    /** Convenience for callers holding a raw temp dir string. */
    public Path extractWav(Path sourcePath, String workDir) {
        return extractWav(sourcePath, Paths.get(workDir));
    }

    /**
     * Cuts the window {@code [startMs, endMs)} out of the source into a small,
     * frame-accurate MP4. This lets Remotion render a ~1-minute file instead of
     * range-seeking the multi-hour source for every clip.
     *
     * <p>Input seeking ({@code -ss} before {@code -i}) plus a re-encode is both
     * fast and frame-accurate in modern ffmpeg — accuracy matters here because a
     * keyframe-snapped cut would drift the start and desync the burned captions.
     *
     * @return the path to the written subclip
     */
    public Path cutSubclip(Path sourcePath, int startMs, int endMs, Path outPath) {
        try {
            Files.createDirectories(outPath.getParent());
            double startSec = Math.max(0, startMs) / 1000.0;
            double durSec = Math.max(1, endMs - startMs) / 1000.0;

            List<String> cmd = List.of(
                    ffmpegProperties.getBinary().getPath(),
                    "-y",
                    "-ss", String.format(java.util.Locale.ROOT, "%.3f", startSec),
                    "-i", sourcePath.toString(),
                    "-t", String.format(java.util.Locale.ROOT, "%.3f", durSec),
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "20",
                    // Force a universally-decodable 8-bit 4:2:0 stream. A .mov
                    // source can be 4:2:2 / 10-bit (ProRes etc.); libx264 would
                    // otherwise preserve that, and Remotion's OffthreadVideo frame
                    // extractor stalls on non-4:2:0 → delayRender timeout.
                    "-pix_fmt", "yuv420p",
                    "-profile:v", "high",
                    "-level", "4.1",
                    // Keyframe every ~1s so seeking to any frame is cheap. With a
                    // sparse GOP, extracting a frame deep in the clip decodes a long
                    // run and a single frame can exceed the 123s render timeout.
                    "-g", "30",
                    "-keyint_min", "30",
                    "-sc_threshold", "0",
                    "-c:a", "aac",
                    "-movflags", "+faststart",
                    outPath.toString()
            );

            log.info("[AudioExtractor] Cutting subclip [{} ms, {} ms) → {}", startMs, endMs, outPath);
            runFfmpeg(cmd);

            if (!Files.exists(outPath) || Files.size(outPath) == 0) {
                throw new IllegalStateException("ffmpeg produced no subclip for " + sourcePath);
            }
            return outPath;
        } catch (Exception e) {
            throw new RuntimeException("Subclip cut failed for " + sourcePath, e);
        }
    }

    private static final java.util.regex.Pattern DURATION_PATTERN =
            java.util.regex.Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)");

    /**
     * Probes the source's duration in whole seconds (rounded up) by parsing
     * ffmpeg's own {@code Duration:} banner. Used to charge credits BEFORE any
     * compute starts. Uses the configured ffmpeg binary — no separate ffprobe
     * dependency — so it works wherever extraction works.
     *
     * @return duration in seconds, or 0 when it cannot be determined
     */
    public int probeDurationSeconds(Path sourcePath) {
        try {
            Process process = new ProcessBuilder(
                    ffmpegProperties.getBinary().getPath(), "-i", sourcePath.toString())
                    .redirectErrorStream(true)
                    .start();

            String banner;
            try (var reader = process.inputReader()) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                banner = sb.toString();
            }
            process.waitFor(2, TimeUnit.MINUTES); // ffmpeg -i (no output file) exits fast

            var m = DURATION_PATTERN.matcher(banner);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                int s = Integer.parseInt(m.group(3));
                double total = h * 3600.0 + min * 60.0 + s
                        + Double.parseDouble("0." + m.group(4));
                return (int) Math.ceil(total);
            }
            log.warn("[AudioExtractor] Could not parse duration for {}", sourcePath);
            return 0;
        } catch (Exception e) {
            log.warn("[AudioExtractor] Duration probe failed for {} — {}", sourcePath, e.getMessage());
            return 0;
        }
    }
}

package com.BossAi.bossAi.service.podcast;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    /** @see #extractWav(String, Path) — convenience for a local {@link Path} source. */
    public Path extractWav(Path sourcePath, Path workDir) {
        return extractWav(sourcePath.toString(), workDir);
    }

    /**
     * @param source  ffmpeg input — a local path OR a directly-fetchable URL
     *                (presigned R2 GET). A URL lets ffmpeg stream the audio
     *                without the whole (multi-GB) source ever hitting local disk.
     * @param workDir directory to write the extracted wav into
     * @return path to the extracted {@code episode.wav}
     */
    public Path extractWav(String source, Path workDir) {
        try {
            Files.createDirectories(workDir);
            Path out = workDir.resolve("episode.wav");

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegProperties.getBinary().getPath());
            cmd.add("-y");
            cmd.addAll(inputArgs(source));  // http reconnect flags (if URL) + -i source
            cmd.add("-vn");                 // drop video
            cmd.add("-ac");
            cmd.add("1");                   // mono
            cmd.add("-ar");
            cmd.add("16000");               // 16 kHz
            cmd.add("-c:a");
            cmd.add("pcm_s16le");           // uncompressed PCM WAV
            cmd.add(out.toString());

            log.info("[AudioExtractor] Extracting audio — src: {} → {}", source, out);
            runFfmpeg(cmd);

            if (!Files.exists(out) || Files.size(out) == 0) {
                throw new IllegalStateException("ffmpeg produced no audio for " + source);
            }
            log.info("[AudioExtractor] Extracted {} bytes of audio", Files.size(out));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Audio extraction failed for " + source, e);
        }
    }

    /**
     * ffmpeg input args for a source. For an http(s) URL, prepends reconnect
     * options so a transient network blip during a long streamed read doesn't
     * abort the whole job; always ends with {@code -i <source>}.
     */
    private static List<String> inputArgs(String source) {
        List<String> args = new ArrayList<>();
        if (isUrl(source)) {
            args.add("-reconnect");
            args.add("1");
            args.add("-reconnect_streamed");
            args.add("1");
            args.add("-reconnect_delay_max");
            args.add("5");
        }
        args.add("-i");
        args.add(source);
        return args;
    }

    private static boolean isUrl(String source) {
        return source != null && (source.startsWith("http://") || source.startsWith("https://"));
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
    /** @see #cutSubclip(String, int, int, Path) — convenience for a local {@link Path} source. */
    public Path cutSubclip(Path sourcePath, int startMs, int endMs, Path outPath) {
        return cutSubclip(sourcePath.toString(), startMs, endMs, outPath);
    }

    /**
     * @param source ffmpeg input — a local path OR a directly-fetchable URL. For
     *               a URL, {@code -ss} before {@code -i} makes ffmpeg HTTP-range
     *               seek to the window and read only the bytes it needs, so
     *               cutting a clip out of a 20 GB remote source stays cheap.
     */
    public Path cutSubclip(String source, int startMs, int endMs, Path outPath) {
        try {
            Files.createDirectories(outPath.getParent());
            double startSec = Math.max(0, startMs) / 1000.0;
            double durSec = Math.max(1, endMs - startMs) / 1000.0;

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegProperties.getBinary().getPath());
            cmd.add("-y");
            if (isUrl(source)) {
                cmd.add("-reconnect");
                cmd.add("1");
                cmd.add("-reconnect_streamed");
                cmd.add("1");
                cmd.add("-reconnect_delay_max");
                cmd.add("5");
            }
            cmd.add("-ss");
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", startSec));
            cmd.add("-i");
            cmd.add(source);
            cmd.add("-t");
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", durSec));
            cmd.addAll(List.of(
                    // Cap decode resolution at the render size. The final clip is
                    // 1080x1920, but a 4K phone .mov would otherwise be decoded at
                    // full 4K per frame in Remotion's compositor (twice, for
                    // blur-fill) — enough memory to get the compositor OOM-killed
                    // (SIGKILL) mid-render. Shrink-only (never upscale), aspect
                    // preserved, even dimensions for yuv420p.
                    "-vf", "scale='min(1920,iw)':'min(1920,ih)':"
                            + "force_original_aspect_ratio=decrease:force_divisible_by=2",
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
            ));

            log.info("[AudioExtractor] Cutting subclip [{} ms, {} ms) → {}", startMs, endMs, outPath);
            runFfmpeg(cmd);

            if (!Files.exists(outPath) || Files.size(outPath) == 0) {
                throw new IllegalStateException("ffmpeg produced no subclip for " + source);
            }
            return outPath;
        } catch (Exception e) {
            throw new RuntimeException("Subclip cut failed for " + source, e);
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
    /** @see #probeDurationSeconds(String) — convenience for a local {@link Path} source. */
    public int probeDurationSeconds(Path sourcePath) {
        return probeDurationSeconds(sourcePath.toString());
    }

    /**
     * @param source ffmpeg input — a local path OR a URL. Over a URL, ffmpeg only
     *               reads the container headers (range requests), not the whole
     *               file, so probing a 20 GB remote episode is cheap.
     */
    public int probeDurationSeconds(String source) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegProperties.getBinary().getPath());
            cmd.addAll(inputArgs(source));  // http reconnect flags (if URL) + -i source

            Process process = new ProcessBuilder(cmd)
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
            log.warn("[AudioExtractor] Could not parse duration for {}", source);
            return 0;
        } catch (Exception e) {
            log.warn("[AudioExtractor] Duration probe failed for {} — {}", source, e.getMessage());
            return 0;
        }
    }
}

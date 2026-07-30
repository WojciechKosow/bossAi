package com.BossAi.bossAi.service.generation.step;

import com.BossAi.bossAi.config.properties.FfmpegProperties;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.BossAi.bossAi.service.generation.GenerationStepName;
import com.BossAi.bossAi.service.generation.context.SceneAsset;
import com.BossAi.bossAi.service.generation.context.ScriptResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ImageToClipStep — converts a static image (PNG/JPG) into an MP4 clip.
 *
 * PHASE 2 — a new pipeline step for IMAGE-type scenes.
 *
 * The problem it solves:
 *   VideoStep costs ~$0.07-0.14 per scene (Kling/Runway).
 *   For a 7-scene educational video = ~$0.50-1.00 on video alone.
 *   Most scenes don't "need" animation — a static image with a dynamic
 *   tekstem overlay robi ten sam efekt i kosztuje $0.
 *
 * How it works:
 *   FFmpeg -loop 1 -i image.png -t [duration] -vf scale=1080:1920 clip.mp4
 *   Optionally: a Ken Burns effect (slow zoom) for the illusion of movement.
 *
 * Pipeline:
 *   1. Fetch the PNG from imageUrl (from ImageStep) over HTTP
 *   2. Convert to MP4 via an FFmpeg loop (durationMs from SceneAsset)
 *   3. Optionally add a Ken Burns zoom for movement
 *   4. Save to workDir, set scene.videoLocalPath
 *
 * Ken Burns effect:
 *   A slow zoom in 100%→120% over the whole scene duration.
 *   Makes a static image look "alive" on TikTok.
 *   Enabled by default — disable via kenBurnsEnabled=false in properties.
 *
 * Called by VideoStep in the mixed-media pipeline:
 *   VideoStep.execute() checks the mediaAssignment per scene:
 *     IMAGE → deleguje do ImageToClipStep
 *     VIDEO → calls fal.ai as before
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageToClipStep {

    private final FfmpegProperties ffmpegProperties;

    @Value("${ffmpeg.temp.dir:/tmp/bossai/render}")
    private String tempDir;

    @Value("${pipeline.ken-burns.enabled:true}")
    private boolean kenBurnsEnabled;

    /**
     * Converts a single image into an MP4 clip.
     *
     * @param scene     a SceneAsset with imageUrl filled in (from ImageStep)
     * @param workDir   katalog roboczy FFmpeg dla tej generacji
     * @return path to the generated MP4
     */
    public String convertImageToClip(SceneAsset scene, Path workDir) throws Exception {
        if (scene.getImageUrl() == null || scene.getImageUrl().isBlank()) {
            throw new IllegalStateException(
                    "[ImageToClipStep] Scene " + scene.getIndex() + " has no imageUrl — ImageStep did not run");
        }

        log.info("[ImageToClipStep] Scene {} — imageUrl: {}, durationMs: {}",
                scene.getIndex(), scene.getImageUrl(), scene.getDurationMs());

        // Fetch the PNG from the URL
        Path imagePath = downloadImage(scene.getImageUrl(), scene.getIndex(), workDir);

        // Convert to MP4
        String outputFilename = String.format("scene_%02d_image_clip.mp4", scene.getIndex());
        Path outputPath = workDir.resolve(outputFilename);

        if (kenBurnsEnabled) {
            runKenBurnsConvert(imagePath, scene.getDurationMs(), outputPath);
        } else {
            runStaticConvert(imagePath, scene.getDurationMs(), outputPath);
        }

        log.info("[ImageToClipStep] Scene {} DONE → {}", scene.getIndex(), outputPath);
        return outputPath.toString();
    }

    /**
     * Converts a local image file into an MP4 clip — without downloading over HTTP.
     * Used for user-uploaded custom image assets (they have no imageUrl, they're in storage).
     *
     * @param localImagePath path to the image file on disk
     * @param durationMs     czas trwania klipu
     * @param sceneIndex     the scene index (for the output file name)
     * @param workDir        katalog roboczy FFmpeg
     * @return path to the generated MP4
     */
    public String convertLocalImageToClip(Path localImagePath, int durationMs,
                                           int sceneIndex, Path workDir) throws Exception {
        log.info("[ImageToClipStep] Scene {} — local image: {}, durationMs: {}",
                sceneIndex, localImagePath, durationMs);

        String outputFilename = String.format("scene_%02d_image_clip.mp4", sceneIndex);
        Path outputPath = workDir.resolve(outputFilename);

        if (kenBurnsEnabled) {
            runKenBurnsConvert(localImagePath, durationMs, outputPath);
        } else {
            runStaticConvert(localImagePath, durationMs, outputPath);
        }

        log.info("[ImageToClipStep] Scene {} DONE → {}", sceneIndex, outputPath);
        return outputPath.toString();
    }

    // =========================================================================
    // FFMPEG CONVERSIONS
    // =========================================================================

    /**
     * Static conversion — the image is displayed for durationMs without movement.
     *
     * ffmpeg -loop 1 -i image.png -t [duration]
     *   -vf "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black"
     *   -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p
     *   -r 30 output.mp4
     */
    private void runStaticConvert(Path imagePath, int durationMs, Path output) throws Exception {
        double durationSec = durationMs / 1000.0;

        // Scale + pad to 1080x1920 preserving aspect ratio, black bars if needed
        String vf = "scale=1080:1920:force_original_aspect_ratio=decrease," +
                "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black," +
                "setsar=1";

        List<String> cmd = buildConvertCmd(imagePath, durationSec, vf, output);
        runCommand(cmd, "static-convert-scene-" + imagePath.getFileName());
    }

    /**
     * Ken Burns effect — a slow zoom in 100%→120% over the whole scene.
     *
     * Effect: the image looks like footage, not a static graphic.
     * Bardzo popularny w TikTok educational content.
     *
     * FFmpeg zoompan filter:
     *   z='min(zoom+0.0003,1.2)' — zoom from 1.0 to 1.2 over the whole scene
     *   d=[frames]               — liczba klatek = durationSec * fps (30fps)
     *   x='iw/2-(iw/zoom/2)'    — centruj w osi X
     *   y='ih/2-(ih/zoom/2)'    — centruj w osi Y
     *   s=1080x1920              — output size
     */
    private void runKenBurnsConvert(Path imagePath, int durationMs, Path output) throws Exception {
        double durationSec = durationMs / 1000.0;
        int totalFrames    = (int) (durationSec * 30); // 30fps

        // Compute the zoom step: we want to go from 1.0 to 1.2 over totalFrames frames
        // zoom_step = 0.2 / totalFrames (but no more than 0.002 so it's not too fast)
        double zoomStep = Math.min(0.2 / Math.max(totalFrames, 1), 0.002);

        String zoompan = String.format(Locale.US,
                "zoompan=z='min(zoom+%.6f,1.2)':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                zoomStep, totalFrames
        );;

        // scale before zoompan to make sure the input is appropriate
        String vf = "scale=1080:1920:force_original_aspect_ratio=increase," +
                "crop=1080:1920," +
                zoompan + "," +
                "setsar=1";

        List<String> cmd = buildConvertCmd(imagePath, durationSec, vf, output);
        runCommand(cmd, "ken-burns-scene-" + scene(imagePath));
    }

    private List<String> buildConvertCmd(Path input, double durationSec, String vf, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegProperties.getBinary().getPath());
        cmd.add("-y");
        cmd.addAll(List.of("-loop", "1"));
        cmd.addAll(List.of("-i", input.toString()));
        cmd.addAll(List.of("-vf", vf));
        cmd.addAll(List.of("-t", String.format(Locale.US, "%.3f", durationSec)));
        cmd.addAll(List.of("-c:v", "libx264"));
        cmd.addAll(List.of("-preset", "fast"));
        cmd.addAll(List.of("-crf", "23"));
        cmd.addAll(List.of("-pix_fmt", "yuv420p")); // required by some players
        cmd.addAll(List.of("-r", "30"));             // constant framerate
        cmd.addAll(List.of("-an"));                  // brak audio — dodane w RenderStep mix
        cmd.addAll(List.of("-movflags", "+faststart")); // moov atom at the start — required by Remotion (Chromium seek)
        cmd.add(output.toString());
        return cmd;
    }

    // =========================================================================
    // DOWNLOAD IMAGE
    // =========================================================================

    /**
     * Downloads the image from a URL (fal.ai CDN) and saves it locally.
     * We use java.net.http to avoid bringing an OkHttp dependency here
     * (OkHttp is in FalAiService, but that's a @Service — we don't want a circular dependency).
     */
    private Path downloadImage(String imageUrl, int sceneIndex, Path workDir) throws Exception {
        String ext = imageUrl.contains(".png") ? ".png" : ".jpg";
        Path imagePath = workDir.resolve(String.format("scene_%02d_image%s", sceneIndex, ext));

        if (Files.exists(imagePath)) {
            log.debug("[ImageToClipStep] Image already downloaded: {}", imagePath);
            return imagePath;
        }

        log.debug("[ImageToClipStep] Pobieranie obrazu z: {}", imageUrl);

        var client   = java.net.http.HttpClient.newHttpClient();
        var request  = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .build();

        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("[ImageToClipStep] HTTP " + response.statusCode()
                    + " while downloading the image: " + imageUrl);
        }

        Files.write(imagePath, response.body());
        log.debug("[ImageToClipStep] Pobrano {} bytes → {}", response.body().length, imagePath);
        return imagePath;
    }

    // =========================================================================
    // FFMPEG PROCESS
    // =========================================================================

    private void runCommand(List<String> cmd, String phase) throws Exception {
        log.info("[ImageToClipStep][{}] Komenda: {}", phase, String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffmpegLog = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegLog.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String logTail = ffmpegLog.length() > 1500
                    ? ffmpegLog.substring(ffmpegLog.length() - 1500)
                    : ffmpegLog.toString();
            throw new RuntimeException("[ImageToClipStep][" + phase + "] FFmpeg kod " + exitCode
                    + "\n" + logTail);
        }

        log.debug("[ImageToClipStep][{}] OK", phase);
    }

    private String scene(Path p) {
        String name = p.getFileName().toString();
        return name.length() > 20 ? name.substring(0, 20) : name;
    }
}
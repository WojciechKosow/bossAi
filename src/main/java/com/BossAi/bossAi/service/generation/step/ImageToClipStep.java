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
 * ImageToClipStep — konwertuje statyczny obraz (PNG/JPG) do klipu MP4.
 *
 * FAZA 2 — nowy krok pipeline dla IMAGE-type scen.
 *
 * Problem który rozwiązuje:
 *   VideoStep kosztuje ~$0.07-0.14 per scena (Kling/Runway).
 *   Dla 7-scenowego educational video = ~$0.50-1.00 tylko na video.
 *   Większość scen nie "potrzebuje" animacji — statyczny obraz z dynamicznym
 *   tekstem overlay robi ten sam efekt i kosztuje $0.
 *
 * Jak działa:
 *   FFmpeg -loop 1 -i image.png -t [duration] -vf scale=1080:1920 clip.mp4
 *   Opcjonalnie: Ken Burns effect (powolny zoom) dla złudzenia ruchu.
 *
 * Pipeline:
 *   1. Pobierz PNG z imageUrl (z ImageStep) przez HTTP
 *   2. Konwertuj do MP4 przez FFmpeg loop (durationMs ze SceneAsset)
 *   3. Opcjonalnie dodaj Ken Burns zoom dla dynamiki
 *   4. Zapisz do workDir, ustaw scene.videoLocalPath
 *
 * Ken Burns effect:
 *   Powolny zoom in 100%→120% przez cały czas trwania sceny.
 *   Sprawia że statyczny obraz wygląda "żywo" na TikToku.
 *   Włączony domyślnie — wyłącz przez kenBurnsEnabled=false w properties.
 *
 * Wywoływany przez VideoStep w mixed media pipeline:
 *   VideoStep.execute() sprawdza mediaAssignment per scena:
 *     IMAGE → deleguje do ImageToClipStep
 *     VIDEO → wywołuje fal.ai jak poprzednio
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
     * Konwertuje jeden obraz do klipu MP4.
     *
     * @param scene     SceneAsset z wypełnionym imageUrl (z ImageStep)
     * @param workDir   katalog roboczy FFmpeg dla tej generacji
     * @return ścieżka do wygenerowanego MP4
     */
    public String convertImageToClip(SceneAsset scene, Path workDir) throws Exception {
        if (scene.getImageUrl() == null || scene.getImageUrl().isBlank()) {
            throw new IllegalStateException(
                    "[ImageToClipStep] Scena " + scene.getIndex() + " nie ma imageUrl — ImageStep się nie wykonał");
        }

        log.info("[ImageToClipStep] Scena {} — imageUrl: {}, durationMs: {}",
                scene.getIndex(), scene.getImageUrl(), scene.getDurationMs());

        // Pobierz PNG z URL
        Path imagePath = downloadImage(scene.getImageUrl(), scene.getIndex(), workDir);

        // Konwertuj do MP4
        String outputFilename = String.format("scene_%02d_image_clip.mp4", scene.getIndex());
        Path outputPath = workDir.resolve(outputFilename);

        if (kenBurnsEnabled) {
            runKenBurnsConvert(imagePath, scene.getDurationMs(), outputPath);
        } else {
            runStaticConvert(imagePath, scene.getDurationMs(), outputPath);
        }

        log.info("[ImageToClipStep] Scena {} DONE → {}", scene.getIndex(), outputPath);
        return outputPath.toString();
    }

    // =========================================================================
    // FFMPEG CONVERSIONS
    // =========================================================================

    /**
     * Statyczna konwersja — obraz wyświetlany przez durationMs bez ruchu.
     *
     * ffmpeg -loop 1 -i image.png -t [duration]
     *   -vf "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black"
     *   -c:v libx264 -preset fast -crf 23 -pix_fmt yuv420p
     *   -r 30 output.mp4
     */
    private void runStaticConvert(Path imagePath, int durationMs, Path output) throws Exception {
        double durationSec = durationMs / 1000.0;

        // Scale + pad do 1080x1920 zachowując proporcje, czarne pasy jeśli potrzeba
        String vf = "scale=1080:1920:force_original_aspect_ratio=decrease," +
                "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black," +
                "setsar=1";

        List<String> cmd = buildConvertCmd(imagePath, durationSec, vf, output);
        runCommand(cmd, "static-convert-scene-" + imagePath.getFileName());
    }

    /**
     * Ken Burns effect — powolny zoom in 100%→120% przez cały czas sceny.
     *
     * Efekt: obraz wygląda jak nagranie, nie jak statyczna grafika.
     * Bardzo popularny w TikTok educational content.
     *
     * FFmpeg zoompan filter:
     *   z='min(zoom+0.0003,1.2)' — zoom od 1.0 do 1.2 przez całą scenę
     *   d=[frames]               — liczba klatek = durationSec * fps (30fps)
     *   x='iw/2-(iw/zoom/2)'    — centruj w osi X
     *   y='ih/2-(ih/zoom/2)'    — centruj w osi Y
     *   s=1080x1920              — output size
     */
    private void runKenBurnsConvert(Path imagePath, int durationMs, Path output) throws Exception {
        double durationSec = durationMs / 1000.0;
        int totalFrames    = (int) (durationSec * 30); // 30fps

        // Oblicz step zooma: chcemy dojść z 1.0 do 1.2 przez totalFrames klatek
        // zoom_step = 0.2 / totalFrames (ale nie więcej niż 0.002 żeby nie było za szybko)
        double zoomStep = Math.min(0.2 / Math.max(totalFrames, 1), 0.002);

        String zoompan = String.format(Locale.US,
                "zoompan=z='min(zoom+%.6f,1.2)':d=%d:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30",
                zoomStep, totalFrames
        );;

        // scale przed zoompan żeby mieć pewność że wejście jest odpowiednie
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
        cmd.addAll(List.of("-pix_fmt", "yuv420p")); // wymagane przez niektóre players
        cmd.addAll(List.of("-r", "30"));             // stały framerate
        cmd.addAll(List.of("-an"));                  // brak audio — dodane w RenderStep mix
        cmd.add(output.toString());
        return cmd;
    }

    // =========================================================================
    // DOWNLOAD IMAGE
    // =========================================================================

    /**
     * Pobiera obraz z URL (fal.ai CDN) i zapisuje lokalnie.
     * Używamy java.net.http żeby nie wnosić OkHttp dependency tutaj
     * (OkHttp jest w FalAiService, ale tam jest @Service — nie chcemy circular).
     */
    private Path downloadImage(String imageUrl, int sceneIndex, Path workDir) throws Exception {
        String ext = imageUrl.contains(".png") ? ".png" : ".jpg";
        Path imagePath = workDir.resolve(String.format("scene_%02d_image%s", sceneIndex, ext));

        if (Files.exists(imagePath)) {
            log.debug("[ImageToClipStep] Obraz już pobrany: {}", imagePath);
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
                    + " pobierając obraz: " + imageUrl);
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
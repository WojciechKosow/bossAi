package com.BossAi.bossAi.service.BeatDetection;

import com.BossAi.bossAi.service.audio.AudioAnalysisClient;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BeatDetectionService — wykrywa beaty w pliku audio.
 *
 * Strategia: Python/librosa (AudioAnalysisClient) -> fallback FFmpeg astats.
 * Python daje prawdziwy beat map (128+ beatow), FFmpeg daje przyblizone energy peaks (czesto 0).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BeatDetectionServiceImpl implements BeatDetectionService {

    private final AudioAnalysisClient audioAnalysisClient;

    @Override
    public List<Integer> detectBeats(String audioPath) {
        return detectBeats(audioPath, null);
    }

    @Override
    public List<Integer> detectBeats(String audioPath, GenerationContext context) {

        // 1. Proba Python/librosa (dokladny beat map)
        try {
            List<Integer> pythonBeats = detectViaPython(audioPath, context);
            if (pythonBeats != null && !pythonBeats.isEmpty()) {
                log.info("[BeatDetection] Python OK — {} beats", pythonBeats.size());
                return pythonBeats;
            }
        } catch (Exception e) {
            log.warn("[BeatDetection] Python failed — fallback to FFmpeg: {}", e.getMessage());
        }

        // 2. Fallback: stary FFmpeg astats
        log.info("[BeatDetection] FFmpeg fallback");
        return detectViaFfmpeg(audioPath);
    }

    /**
     * Wykrywa beaty przez Python/FastAPI microservice (librosa beat_track).
     * Cachuje AudioAnalysisResponse w kontekscie (jesli dostepny),
     * zeby MusicAnalysisService nie musial wolac Pythona ponownie.
     */
    private List<Integer> detectViaPython(String audioPath, GenerationContext context) throws Exception {
        Path path = Path.of(audioPath);
        if (!Files.exists(path)) return null;

        // Sprawdz cache w kontekscie
        AudioAnalysisResponse response = (context != null) ? context.getCachedAudioAnalysis() : null;

        if (response == null) {
            byte[] audioBytes = Files.readAllBytes(path);
            String filename = path.getFileName().toString();
            response = audioAnalysisClient.analyzeAudio(audioBytes, filename);

            // Cachuj w kontekscie
            if (context != null && response != null) {
                context.setCachedAudioAnalysis(response);
            }
        } else {
            log.info("[BeatDetection] Using cached AudioAnalysisResponse from context");
        }

        if (response == null || response.beats() == null || response.beats().isEmpty()) {
            return null;
        }

        // Konwertuj List<Double> (sekundy) -> List<Integer> (ms)
        List<Integer> beats = new ArrayList<>();
        for (Double beatTimeSec : response.beats()) {
            beats.add((int) (beatTimeSec * 1000));
        }

        log.info("[BeatDetection] Python beat map: {} beats, bpm={}", beats.size(), response.bpm());
        return beats;
    }

    /**
     * Fallback: stary FFmpeg astats (czesto zwraca 0 beatow).
     */
    private List<Integer> detectViaFfmpeg(String audioPath) {
        List<Integer> beats = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-i", audioPath,
                    "-af", "astats=metadata=1:reset=1",
                    "-f", "null",
                    "-"
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            String line;
            int timeMs = 0;

            while ((line = reader.readLine()) != null) {

                if (line.contains("pts_time")) {
                    double sec = extractTime(line);
                    timeMs = (int) (sec * 1000);
                }

                if (line.contains("RMS_level")) {
                    double energy = extractEnergy(line);

                    if (energy > -20) {
                        beats.add(timeMs);
                    }
                }
            }

            process.waitFor();

        } catch (Exception e) {
            throw new RuntimeException("Beat detection failed", e);
        }

        log.info("[BeatDetection] FFmpeg detected {} beats", beats.size());

        return normalize(beats);
    }

    private double extractTime(String line) {
        try {
            int idx = line.indexOf("pts_time:");
            if (idx == -1) return 0;

            String value = line.substring(idx + 9).split(" ")[0];
            return Double.parseDouble(value);

        } catch (Exception e) {
            return 0;
        }
    }

    private double extractEnergy(String line) {
        try {
            int idx = line.indexOf("RMS_level:");
            if (idx == -1) return -100;

            String value = line.substring(idx + 10).trim();
            return Double.parseDouble(value);

        } catch (Exception e) {
            return -100;
        }
    }

    private List<Integer> normalize(List<Integer> beats) {

        List<Integer> result = new ArrayList<>();

        int last = -1000;

        for (int beat : beats) {
            if (beat - last > 300) { // min spacing
                result.add(beat);
                last = beat;
            }
        }

        return result;
    }
}

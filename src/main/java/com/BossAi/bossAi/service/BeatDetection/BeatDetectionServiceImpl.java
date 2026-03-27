package com.BossAi.bossAi.service.BeatDetection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BeatDetectionServiceImpl implements BeatDetectionService {


    @Override
    public List<Integer> detectBeats(String audioPath) {

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

                    if (energy > -20) { // 🔥 threshold
                        beats.add(timeMs);
                    }
                }
            }

            process.waitFor();

        } catch (Exception e) {
            throw new RuntimeException("Beat detection failed", e);
        }

        log.info("[BeatDetection] detected {} beats", beats.size());

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

    private boolean isBeat(double energy) {
        return energy > -22;
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

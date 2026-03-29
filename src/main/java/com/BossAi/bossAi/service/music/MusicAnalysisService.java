package com.BossAi.bossAi.service.music;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * MusicAnalysisService — analizuje strukturę muzyki (energy profile, segmenty).
 *
 * Używa FFmpeg astats do pomiaru RMS energy co ~500ms.
 * Na podstawie profilu energii wykrywa segmenty:
 *   - DROP: nagły skok energii (>50% wzrost w 1-2 oknach)
 *   - BUILD_UP: stopniowy wzrost energii przez >=3 okna
 *   - PEAK: utrzymana wysoka energia (>75 percentyla) przez >=2 okna
 *   - QUIET: niska energia (<25 percentyla) przez >=2 okna
 *   - NORMAL: reszta
 */
@Slf4j
@Service
public class MusicAnalysisService {

    /** Rozmiar okna analizy w ms */
    private static final int WINDOW_MS = 500;

    /**
     * Analizuje plik audio i zwraca pełny profil energii + segmenty.
     */
    public MusicAnalysisResult analyze(String audioPath) {
        log.info("[MusicAnalysis] Analizuję: {}", audioPath);

        List<EnergyPoint> rawEnergy = extractEnergyProfile(audioPath);

        if (rawEnergy.isEmpty()) {
            log.warn("[MusicAnalysis] Brak danych energii — zwracam pusty wynik");
            return new MusicAnalysisResult(0, List.of(), List.of(), 0.0, 120);
        }

        // Normalizuj energię do 0.0-1.0
        List<Double> normalizedProfile = normalizeEnergy(rawEnergy);

        int totalDurationMs = rawEnergy.isEmpty() ? 0
                : rawEnergy.get(rawEnergy.size() - 1).timeMs + WINDOW_MS;

        double avgEnergy = normalizedProfile.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Wykryj segmenty
        List<MusicAnalysisResult.MusicSegment> segments = detectSegments(normalizedProfile);

        // Przybliżony BPM z beat spacing
        int bpm = estimateBpm(rawEnergy);

        log.info("[MusicAnalysis] DONE — duration={}ms, {} windows, {} segmentów, avgEnergy={}, bpm={}",
                totalDurationMs, normalizedProfile.size(), segments.size(),
                String.format("%.2f", avgEnergy), bpm);

        for (MusicAnalysisResult.MusicSegment seg : segments) {
            log.debug("[MusicAnalysis] Segment: {} [{}-{}ms] energy={}",
                    seg.type(), seg.startMs(), seg.endMs(),
                    String.format("%.2f", seg.energy()));
        }

        return new MusicAnalysisResult(totalDurationMs, normalizedProfile, segments, avgEnergy, bpm);
    }

    // =========================================================================
    // FFmpeg ENERGY EXTRACTION
    // =========================================================================

    private record EnergyPoint(int timeMs, double rmsDb) {}

    /**
     * Uruchamia FFmpeg astats i zbiera RMS energy per okno czasowe.
     * astats=metadata=1:reset=1 raportuje statystyki co ramkę audio.
     * Grupujemy po WINDOW_MS.
     */
    private List<EnergyPoint> extractEnergyProfile(String audioPath) {
        List<EnergyPoint> points = new ArrayList<>();

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
                    new InputStreamReader(process.getInputStream()));

            String line;
            double currentTimeMs = 0;
            double windowMaxRms = -100;
            double lastWindowEnd = WINDOW_MS;

            while ((line = reader.readLine()) != null) {
                if (line.contains("pts_time")) {
                    double sec = extractValue(line, "pts_time:");
                    currentTimeMs = sec * 1000;
                }

                if (line.contains("RMS_level")) {
                    double rms = extractValue(line, "RMS_level:");

                    // Grupuj w okna WINDOW_MS
                    if (currentTimeMs >= lastWindowEnd) {
                        // Zapisz okno
                        if (windowMaxRms > -100) {
                            points.add(new EnergyPoint((int) (lastWindowEnd - WINDOW_MS), windowMaxRms));
                        }
                        windowMaxRms = rms;
                        lastWindowEnd = ((int) (currentTimeMs / WINDOW_MS) + 1) * WINDOW_MS;
                    } else {
                        windowMaxRms = Math.max(windowMaxRms, rms);
                    }
                }
            }

            // Ostatnie okno
            if (windowMaxRms > -100) {
                points.add(new EnergyPoint((int) (lastWindowEnd - WINDOW_MS), windowMaxRms));
            }

            process.waitFor();

        } catch (Exception e) {
            log.error("[MusicAnalysis] FFmpeg extraction failed: {}", e.getMessage());
        }

        log.info("[MusicAnalysis] Extracted {} energy windows", points.size());
        return points;
    }

    private double extractValue(String line, String key) {
        try {
            int idx = line.indexOf(key);
            if (idx == -1) return 0;
            String value = line.substring(idx + key.length()).trim().split("\\s")[0];
            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================================
    // NORMALIZACJA
    // =========================================================================

    /**
     * Normalizuje RMS dB do 0.0-1.0.
     * Typowy zakres RMS: -60dB (cisza) do 0dB (max).
     * Mapujemy: -60dB → 0.0, -5dB → 1.0
     */
    private List<Double> normalizeEnergy(List<EnergyPoint> raw) {
        // Znajdź zakres
        double minDb = raw.stream().mapToDouble(p -> p.rmsDb).min().orElse(-60);
        double maxDb = raw.stream().mapToDouble(p -> p.rmsDb).max().orElse(0);

        // Minimalna rozpiętość
        if (maxDb - minDb < 5) {
            minDb = maxDb - 30;
        }

        List<Double> normalized = new ArrayList<>();
        for (EnergyPoint p : raw) {
            double norm = (p.rmsDb - minDb) / (maxDb - minDb);
            normalized.add(Math.max(0.0, Math.min(1.0, norm)));
        }

        return normalized;
    }

    // =========================================================================
    // SEGMENT DETECTION
    // =========================================================================

    /**
     * Wykrywa segmenty muzyczne na podstawie znormalizowanego profilu energii.
     *
     * Algorytm:
     *   1. Oblicz percentyle (25%, 75%) dla progów quiet/peak
     *   2. Skanuj profil okno po oknie, klasyfikuj jako: DROP, BUILD_UP, PEAK, QUIET, NORMAL
     *   3. Łącz sąsiednie okna tego samego typu w segmenty
     */
    private List<MusicAnalysisResult.MusicSegment> detectSegments(List<Double> profile) {
        if (profile.size() < 3) return List.of();

        // Percentyle
        List<Double> sorted = new ArrayList<>(profile);
        sorted.sort(Double::compareTo);
        double p25 = sorted.get(sorted.size() / 4);
        double p75 = sorted.get(sorted.size() * 3 / 4);

        // Klasyfikuj każde okno
        List<MusicAnalysisResult.SegmentType> windowTypes = new ArrayList<>();

        for (int i = 0; i < profile.size(); i++) {
            double energy = profile.get(i);
            double prev = i > 0 ? profile.get(i - 1) : energy;
            double delta = energy - prev;

            if (delta > 0.25 && energy > p75) {
                // Nagły skok + wysoka energia = DROP
                windowTypes.add(MusicAnalysisResult.SegmentType.DROP);
            } else if (i >= 2 && isRising(profile, i, 3) && energy < p75) {
                // Rosnąca energia przez 3+ okna, jeszcze nie peak = BUILD_UP
                windowTypes.add(MusicAnalysisResult.SegmentType.BUILD_UP);
            } else if (energy >= p75) {
                windowTypes.add(MusicAnalysisResult.SegmentType.PEAK);
            } else if (energy <= p25) {
                windowTypes.add(MusicAnalysisResult.SegmentType.QUIET);
            } else {
                windowTypes.add(MusicAnalysisResult.SegmentType.NORMAL);
            }
        }

        // Łącz sąsiednie okna tego samego typu w segmenty
        return mergeWindows(windowTypes, profile);
    }

    /**
     * Sprawdza czy energia rośnie przez ostatnie N okien.
     */
    private boolean isRising(List<Double> profile, int currentIdx, int lookback) {
        if (currentIdx < lookback) return false;
        for (int i = currentIdx - lookback + 1; i <= currentIdx; i++) {
            if (profile.get(i) <= profile.get(i - 1)) return false;
        }
        return true;
    }

    /**
     * Scala sąsiednie okna tego samego typu w segmenty.
     * Segmenty krótsze niż 2 okna (1s) → NORMAL (za krótkie żeby być znaczące).
     * Wyjątek: DROP może trwać 1 okno.
     */
    private List<MusicAnalysisResult.MusicSegment> mergeWindows(
            List<MusicAnalysisResult.SegmentType> types,
            List<Double> profile
    ) {
        List<MusicAnalysisResult.MusicSegment> segments = new ArrayList<>();

        int start = 0;
        while (start < types.size()) {
            MusicAnalysisResult.SegmentType type = types.get(start);
            int end = start + 1;

            // Extend while same type or compatible merge
            while (end < types.size() && types.get(end) == type) {
                end++;
            }

            int windowCount = end - start;
            int minWindows = (type == MusicAnalysisResult.SegmentType.DROP) ? 1 : 2;

            if (windowCount >= minWindows) {
                // Oblicz średnią energię segmentu
                double avgEnergy = 0;
                for (int i = start; i < end; i++) {
                    avgEnergy += profile.get(i);
                }
                avgEnergy /= windowCount;

                segments.add(new MusicAnalysisResult.MusicSegment(
                        start * WINDOW_MS,
                        end * WINDOW_MS,
                        type,
                        avgEnergy
                ));
            }

            start = end;
        }

        return segments;
    }

    // =========================================================================
    // BPM ESTIMATION
    // =========================================================================

    /**
     * Przybliżony BPM z odstępów między punktami wysokiej energii.
     */
    private int estimateBpm(List<EnergyPoint> raw) {
        // Znajdź punkty > -15dB (wyraźne beaty)
        List<Integer> beatTimes = new ArrayList<>();
        for (EnergyPoint p : raw) {
            if (p.rmsDb > -15) {
                if (beatTimes.isEmpty() || p.timeMs - beatTimes.get(beatTimes.size() - 1) > 200) {
                    beatTimes.add(p.timeMs);
                }
            }
        }

        if (beatTimes.size() < 4) return 120; // fallback

        // Średni odstęp między beatami
        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < beatTimes.size(); i++) {
            int interval = beatTimes.get(i) - beatTimes.get(i - 1);
            if (interval > 200 && interval < 2000) { // realistyczne
                intervals.add(interval);
            }
        }

        if (intervals.isEmpty()) return 120;

        double avgIntervalMs = intervals.stream().mapToInt(Integer::intValue).average().orElse(500);
        int bpm = (int) Math.round(60000.0 / avgIntervalMs);

        // Clamp do realistycznego zakresu
        return Math.max(60, Math.min(200, bpm));
    }
}

package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DirectorValidator — waliduje DirectorPlan przed przekazaniem do RenderStep.
 *
 * FAZA 1 BUGFIX:
 *
 *   1. Tolerancja timing — poprzednia wartość 300ms była za mała.
 *      GPT-4o czasem generuje cuts z sumą o 400-600ms odbiegającą od durationMs.
 *      Nowa tolerancja: 10% durationMs, minimum 500ms.
 *      Zbyt mała tolerancja powodowała wyjątek "Cuts don't match scene duration"
 *      i fallback na prosty plan, tracąc cały AI director output.
 *
 *   2. Walidacja ciągłości cuts — sprawdzamy czy end każdego cut = start następnego.
 *      Luki między cuts powodują ciszę i czarny ekran w FFmpeg concat.
 *      Naprawiamy automatycznie zamiast rzucać wyjątek (bardziej odporny pipeline).
 *
 *   3. Walidacja zerowych cuts — cut endMs <= startMs powoduje FFmpeg crash.
 *      Teraz filtrujemy je z ostrzeżeniem zamiast rzucać wyjątek.
 *
 *   4. Lepsze komunikaty błędów — pokazują które sceny i jakie wartości,
 *      żeby łatwiej debugować w logach.
 */
@Slf4j
@Component
public class DirectorValidator {

    /**
     * Tolerancja bazowa w ms — cuts mogą nie pokrywać dokładnie durationMs.
     * GPT-4o nie jest perfekcyjny w liczeniu ms.
     */
    private static final int BASE_TOLERANCE_MS = 500;

    /**
     * Tolerancja procentowa — 10% czasu sceny.
     * Dla sceny 5000ms → tolerancja = max(500, 500) = 500ms.
     * Dla sceny 10000ms → tolerancja = max(500, 1000) = 1000ms.
     */
    private static final double TOLERANCE_PERCENT = 0.10;

    public void validate(DirectorPlan plan, GenerationContext context) {
        if (plan.getScenes() == null || plan.getScenes().isEmpty()) {
            throw new IllegalArgumentException("[DirectorValidator] DirectorPlan nie zawiera scen");
        }

        for (SceneDirection scene : plan.getScenes()) {
            validateScene(scene, context);
        }

        log.info("[DirectorValidator] Plan OK — {} scen zwalidowanych", plan.getScenes().size());
    }

    private void validateScene(SceneDirection scene, GenerationContext context) {
        int sceneIndex = scene.getSceneIndex();

        int expectedDurationMs = context.getScenes().stream()
                .filter(s -> s.getIndex() == sceneIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "[DirectorValidator] Scena " + sceneIndex + " nie istnieje w kontekście"))
                .getDurationMs();

        if (scene.getCuts() == null || scene.getCuts().isEmpty()) {
            log.warn("[DirectorValidator] Scena {} nie ma cuts — zostanie pominięta przez RenderStep",
                    sceneIndex);
            return;
        }

        // Filtruj i auto-napraw zerowe / ujemne cuts
        List<Cut> validCuts = filterInvalidCuts(scene, sceneIndex);
        scene.setCuts(validCuts);

        if (validCuts.isEmpty()) {
            throw new IllegalArgumentException(
                    "[DirectorValidator] Scena " + sceneIndex + " nie ma żadnych prawidłowych cuts");
        }

        // Auto-napraw ciągłość cuts (luki między nimi)
        repairContinuity(scene, sceneIndex, expectedDurationMs);

        // Sprawdź sumę z tolerancją
        validateTotalDuration(scene, sceneIndex, expectedDurationMs);
    }

    /**
     * Usuwa cuts z endMs <= startMs (zerowa lub ujemna długość).
     * FFmpeg crashuje na takich klipach.
     */
    private List<Cut> filterInvalidCuts(SceneDirection scene, int sceneIndex) {
        List<Cut> valid = scene.getCuts().stream()
                .filter(cut -> {
                    if (cut.getEndMs() <= cut.getStartMs()) {
                        log.warn("[DirectorValidator] Scena {} — cut [{}-{}] ma zerową/ujemną długość, pomijam",
                                sceneIndex, cut.getStartMs(), cut.getEndMs());
                        return false;
                    }
                    return true;
                })
                .toList();

        int removed = scene.getCuts().size() - valid.size();
        if (removed > 0) {
            log.warn("[DirectorValidator] Scena {} — usunięto {} nieprawidłowych cuts",
                    sceneIndex, removed);
        }

        return valid;
    }

    /**
     * Naprawia luki i nakładki między cuts.
     *
     * Jeśli cut[i].endMs != cut[i+1].startMs → ustawiamy cut[i+1].startMs = cut[i].endMs.
     * To eliminuje ciszę/czarny ekran w wynikowym filmie.
     *
     * Ostatni cut jest rozciągany/przycinany do expectedDurationMs jeśli różnica
     * mieści się w tolerancji.
     */
    private void repairContinuity(SceneDirection scene, int sceneIndex, int expectedDurationMs) {
        List<Cut> cuts = scene.getCuts();

        // Napraw luki między sąsiednimi cuts
        for (int i = 0; i < cuts.size() - 1; i++) {
            Cut current = cuts.get(i);
            Cut next    = cuts.get(i + 1);

            if (current.getEndMs() != next.getStartMs()) {
                log.warn("[DirectorValidator] Scena {} — luka między cut[{}]({}-{}) a cut[{}]({}-{}), naprawiam",
                        sceneIndex, i, current.getStartMs(), current.getEndMs(),
                        i + 1, next.getStartMs(), next.getEndMs());
                next.setStartMs(current.getEndMs());
            }
        }

        // Ostatni cut — dopasuj do expectedDurationMs jeśli w tolerancji
        Cut lastCut = cuts.get(cuts.size() - 1);
        int tolerance = computeTolerance(expectedDurationMs);

        if (Math.abs(lastCut.getEndMs() - expectedDurationMs) <= tolerance) {
            if (lastCut.getEndMs() != expectedDurationMs) {
                log.debug("[DirectorValidator] Scena {} — ostatni cut endMs {} → {} (w tolerancji {}ms)",
                        sceneIndex, lastCut.getEndMs(), expectedDurationMs, tolerance);
                lastCut.setEndMs(expectedDurationMs);
            }
        }
    }

    /**
     * Sprawdza czy suma cuts mieści się w tolerancji.
     * Rzuca wyjątek tylko gdy różnica jest duża — fallback w DirectorStep
     * obsłuży to i wygeneruje prosty plan.
     */
    private void validateTotalDuration(SceneDirection scene, int sceneIndex, int expectedDurationMs) {
        int totalMs = scene.getCuts().stream()
                .mapToInt(c -> c.getEndMs() - c.getStartMs())
                .sum();

        int tolerance = computeTolerance(expectedDurationMs);
        int diff      = Math.abs(totalMs - expectedDurationMs);

        if (diff > tolerance)  {
            throw new IllegalArgumentException(String.format(
                    "[DirectorValidator] Scena %d — suma cuts %dms odbiega od oczekiwanej %dms o %dms " +
                            "(tolerancja %dms). DirectorStep użyje fallback planu.",
                    sceneIndex, totalMs, expectedDurationMs, diff, tolerance));
        }

        log.debug("[DirectorValidator] Scena {} OK — suma cuts {}ms, oczekiwano {}ms (diff {}ms, tolerancja {}ms)",
                sceneIndex, totalMs, expectedDurationMs, diff, tolerance);
    }

    private int computeTolerance(int durationMs) {
        return Math.max(BASE_TOLERANCE_MS, (int) (durationMs * TOLERANCE_PERCENT));
    }
}
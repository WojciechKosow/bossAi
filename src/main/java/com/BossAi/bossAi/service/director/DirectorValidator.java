package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.generation.GenerationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DirectorValidator — validates the DirectorPlan before passing it to RenderStep.
 *
 * PHASE 1 BUGFIX:
 *
 *   1. Timing tolerance — the previous value of 300ms was too small.
 *      GPT-4o sometimes generates cuts whose sum is 400-600ms off from durationMs.
 *      New tolerance: 10% of durationMs, minimum 500ms.
 *      Too small a tolerance caused a "Cuts don't match scene duration" exception
 *      and a fallback to a simple plan, losing the entire AI director output.
 *
 *   2. Cut continuity validation — we check that the end of each cut = the start of the next.
 *      Gaps between cuts cause silence and a black screen in FFmpeg concat.
 *      We fix them automatically instead of throwing an exception (a more robust pipeline).
 *
 *   3. Zero-length cut validation — a cut with endMs <= startMs causes an FFmpeg crash.
 *      We now filter them out with a warning instead of throwing an exception.
 *
 *   4. Better error messages — they show which scenes and what values,
 *      to make debugging in the logs easier.
 */
@Slf4j
@Component
public class DirectorValidator {

    /**
     * Base tolerance in ms — cuts may not cover durationMs exactly.
     * GPT-4o is not perfect at counting ms.
     */
    private static final int BASE_TOLERANCE_MS = 500;

    /**
     * Percentage tolerance — 10% of the scene duration.
     * For a 5000ms scene → tolerance = max(500, 500) = 500ms.
     * For a 10000ms scene → tolerance = max(500, 1000) = 1000ms.
     */
    private static final double TOLERANCE_PERCENT = 0.10;

    public void validate(DirectorPlan plan, GenerationContext context) {
        if (plan.getScenes() == null || plan.getScenes().isEmpty()) {
            throw new IllegalArgumentException("[DirectorValidator] DirectorPlan contains no scenes");
        }

        for (SceneDirection scene : plan.getScenes()) {
            validateScene(scene, context);
        }

        log.info("[DirectorValidator] Plan OK — {} scenes validated", plan.getScenes().size());
    }

    private void validateScene(SceneDirection scene, GenerationContext context) {
        int sceneIndex = scene.getSceneIndex();

        int expectedDurationMs = context.getScenes().stream()
                .filter(s -> s.getIndex() == sceneIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "[DirectorValidator] Scene " + sceneIndex + " does not exist in the context"))
                .getDurationMs();

        if (scene.getCuts() == null || scene.getCuts().isEmpty()) {
            log.warn("[DirectorValidator] Scene {} has no cuts — it will be skipped by RenderStep",
                    sceneIndex);
            return;
        }

        // Filter out and auto-fix zero / negative cuts
        List<Cut> validCuts = filterInvalidCuts(scene, sceneIndex);
        scene.setCuts(validCuts);

        if (validCuts.isEmpty()) {
            throw new IllegalArgumentException(
                    "[DirectorValidator] Scene " + sceneIndex + " has no valid cuts");
        }

        // Auto-fix cut continuity (gaps between them)
        repairContinuity(scene, sceneIndex, expectedDurationMs);

        // Check the sum with tolerance
        validateTotalDuration(scene, sceneIndex, expectedDurationMs);
    }

    /**
     * Removes cuts with endMs <= startMs (zero or negative length).
     * FFmpeg crashes on such clips.
     */
    private List<Cut> filterInvalidCuts(SceneDirection scene, int sceneIndex) {
        List<Cut> valid = scene.getCuts().stream()
                .filter(cut -> {
                    if (cut.getEndMs() <= cut.getStartMs()) {
                        log.warn("[DirectorValidator] Scene {} — cut [{}-{}] has zero/negative length, skipping",
                                sceneIndex, cut.getStartMs(), cut.getEndMs());
                        return false;
                    }
                    return true;
                })
                .toList();

        int removed = scene.getCuts().size() - valid.size();
        if (removed > 0) {
            log.warn("[DirectorValidator] Scene {} — removed {} invalid cuts",
                    sceneIndex, removed);
        }

        return valid;
    }

    /**
     * Fixes gaps and overlaps between cuts.
     *
     * If cut[i].endMs != cut[i+1].startMs → we set cut[i+1].startMs = cut[i].endMs.
     * This eliminates silence/black screen in the resulting video.
     *
     * The last cut is stretched/trimmed to expectedDurationMs if the difference
     * is within tolerance.
     */
    private void repairContinuity(SceneDirection scene, int sceneIndex, int expectedDurationMs) {
        List<Cut> cuts = scene.getCuts();

        // Fix gaps between adjacent cuts
        for (int i = 0; i < cuts.size() - 1; i++) {
            Cut current = cuts.get(i);
            Cut next    = cuts.get(i + 1);

            if (current.getEndMs() != next.getStartMs()) {
                log.warn("[DirectorValidator] Scene {} — gap between cut[{}]({}-{}) and cut[{}]({}-{}), fixing",
                        sceneIndex, i, current.getStartMs(), current.getEndMs(),
                        i + 1, next.getStartMs(), next.getEndMs());
                next.setStartMs(current.getEndMs());
            }
        }

        // Last cut — fit to expectedDurationMs if within tolerance
        Cut lastCut = cuts.get(cuts.size() - 1);
        int tolerance = computeTolerance(expectedDurationMs);

        if (Math.abs(lastCut.getEndMs() - expectedDurationMs) <= tolerance) {
            if (lastCut.getEndMs() != expectedDurationMs) {
                log.debug("[DirectorValidator] Scene {} — last cut endMs {} → {} (within tolerance {}ms)",
                        sceneIndex, lastCut.getEndMs(), expectedDurationMs, tolerance);
                lastCut.setEndMs(expectedDurationMs);
            }
        }
    }

     * Checks whether the sum of cuts is within tolerance.
     * Throws an exception only when the difference is large — the fallback in DirectorStep
     * will handle it and generate a simple plan.
     */
    private void validateTotalDuration(SceneDirection scene, int sceneIndex, int expectedDurationMs) {
        int totalMs = scene.getCuts().stream()
                .mapToInt(c -> c.getEndMs() - c.getStartMs())
                .sum();

        int tolerance = computeTolerance(expectedDurationMs);
        int diff      = Math.abs(totalMs - expectedDurationMs);

        if (diff > tolerance)  {
            throw new IllegalArgumentException(String.format(
                    "[DirectorValidator] Scene %d — sum of cuts %dms deviates from the expected %dms by %dms " +
                            "(tolerance %dms). DirectorStep will use the fallback plan.",
                    sceneIndex, totalMs, expectedDurationMs, diff, tolerance));
        }

        log.debug("[DirectorValidator] Scene {} OK — sum of cuts {}ms, expected {}ms (diff {}ms, tolerance {}ms)",
                sceneIndex, totalMs, expectedDurationMs, diff, tolerance);
    }

    private int computeTolerance(int durationMs) {
        return Math.max(BASE_TOLERANCE_MS, (int) (durationMs * TOLERANCE_PERCENT));
    }
}
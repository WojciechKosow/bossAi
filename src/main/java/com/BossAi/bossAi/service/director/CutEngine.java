package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.SubtitleService;
import com.BossAi.bossAi.service.audio.AudioAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Warstwa C — ENGINE CUTÓW — "mózg montażysty".
 *
 * Łączy trzy warstwy danych:
 *   A) NarrationAnalysis — CO i DLACZEGO (semantyka treści)
 *   B) SpeechTimingAnalysis — GDZIE (pauzy, zdania, tempo mowy)
 *   C) AudioAnalysisResponse — KIEDY (beaty, energia muzyki, sekcje)
 *
 * Plus: EditingIntent — JAK (intencja montażowa, pattern, łuk)
 *
 * Każde cięcie ma POWÓD. Film grammar:
 *   NIE rób: cutów w połowie słowa, w środku myśli
 *   RÓB: cut na końcu zdania, na słowie kluczowym, na zmianie kontekstu
 *
 * Typy cięć:
 *   HARD — zmiana kadru: zmiana topic, importance > 0.75, hook start
 *   SOFT — lekka zmiana: koniec zdania + pauza, spadek energii
 *   MICRO — dynamiczna przebitka: wysoka energia, szybkie tempo, drop
 */
@Slf4j
@Service
public class CutEngine {

    /** Tolerancja trafiania na beat (ms) */
    private static final int BEAT_SNAP_TOLERANCE_MS = 80;

    /** Minimalne ujęcie — poniżej tego NIE tniemy. Jedno słowo + cut = złe. */
    private static final int ABSOLUTE_MIN_CUT_MS = 1500;

    /** Domyślne min/max cut jeśli EditDna nie podaje */
    private static final int DEFAULT_MIN_CUT_MS = 2000;
    private static final int DEFAULT_MAX_CUT_MS = 6000;

    /**
     * Minimalna liczba słów w segmencie.
     * Segment z 1-2 słowami wygląda źle po cut — wymuszamy min 3 słowa.
     */
    private static final int MIN_WORDS_PER_SEGMENT = 3;

    /** Preferowana max liczba użyć jednego assetu. Soft limit — przekraczany gdy trzeba. */
    private static final int PREFERRED_MAX_ASSET_USES = 2;

    /** Absolutny max użyć jednego assetu — powyżej wygląda jak zapętlone. */
    private static final int ABSOLUTE_MAX_ASSET_USES = 4;

    /**
     * Generuje listę uzasadnionych cięć z uwzględnieniem intencji usera.
     *
     * Nowa sygnatura z UserEditIntent — warstwa D.
     * Jeśli user podał wskazówki montażowe (np. "asset 0 = intro"),
     * CutEngine generuje HARD CUT-y wymuszające te role.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount,
            UserEditIntent userEditIntent) {

        return doGenerateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs,
                availableAssetCount, sceneCount, userEditIntent);
    }

    /**
     * Generuje listę uzasadnionych cięć.
     *
     * @param narrationAnalysis analiza semantyczna narracji (warstwa A)
     * @param speechAnalysis    analiza timingów mowy (warstwa B)
     * @param audioAnalysis     analiza muzyki (warstwa C / opcjonalna)
     * @param wordTimings       per-word timestampy z WhisperX
     * @param totalDurationMs   łączny czas filmu
     * @param minCutMs          minimalny czas ujęcia (z EditDna)
     * @param maxCutMs          maksymalny czas ujęcia (z EditDna)
     * @param availableAssetCount  ile unikalnych assetów jest dostępnych
     * @param sceneCount        ile scen (tematycznych segmentów) ma film
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount) {

        return doGenerateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs,
                availableAssetCount, sceneCount, null);
    }

    private List<JustifiedCut> doGenerateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount,
            int sceneCount,
            UserEditIntent userEditIntent) {

        // Enforce sane minimums — nigdy poniżej 1500ms
        if (minCutMs < ABSOLUTE_MIN_CUT_MS) minCutMs = ABSOLUTE_MIN_CUT_MS;
        if (minCutMs <= 0) minCutMs = DEFAULT_MIN_CUT_MS;
        if (maxCutMs <= 0) maxCutMs = DEFAULT_MAX_CUT_MS;
        if (maxCutMs < minCutMs * 2) maxCutMs = minCutMs * 3;

        // === INTELIGENTNY LIMIT SEGMENTÓW ===
        // Filozofia: sceneCount definiuje ILE TEMATÓW jest w filmie.
        // Każdy temat = 1 główny segment. Dodatkowe cuty wewnątrz tematu
        // są dozwolone TYLKO jeśli segment jest wystarczająco długi.
        //
        // Przykład "Top 3 places" (3 sceny, 30s):
        //   - Minimalne cuty: 3 (po jednym per temat)
        //   - Sensowne cuty: 5-6 (3 tematy + parę cutów wewnątrz dłuższych scen)
        //   - NIE: 15+ cutów które mieszają assety

        int effectiveSceneCount = sceneCount > 0 ? sceneCount : availableAssetCount;
        int avgSceneDuration = effectiveSceneCount > 0 ? totalDurationMs / effectiveSceneCount : totalDurationMs;

        // Ile dodatkowych cutów wewnątrz scen? Zależy od długości sceny
        // Scena 3s → 0 dodatkowych, scena 6s → 1 dodatkowy, scena 10s → 2 dodatkowe
        int intraCutsPerScene = Math.max(0, (avgSceneDuration - 4000) / 4000);
        int maxSegments = effectiveSceneCount + (effectiveSceneCount * intraCutsPerScene);

        // Dodatkowy limit od assetów (jeśli mniej assetów niż scen)
        if (availableAssetCount > 0) {
            int maxByAssets = availableAssetCount * ABSOLUTE_MAX_ASSET_USES;
            maxSegments = Math.min(maxSegments, maxByAssets);
        }

        // Minimum: tyle segmentów ile scen (każdy temat musi mieć co najmniej 1)
        maxSegments = Math.max(maxSegments, effectiveSceneCount);

        log.info("[CutEngine] Generating cuts — duration: {}ms, scenes: {}, assets: {}, " +
                        "avgSceneDur: {}ms, intraCuts/scene: {}, maxSegments: {}",
                totalDurationMs, sceneCount, availableAssetCount,
                avgSceneDuration, intraCutsPerScene, maxSegments);

        NarrationAnalysis.EditingIntent intent = narrationAnalysis.getEditingIntent();

        // === KROK 1: Zbierz wszystkie potencjalne punkty cięcia ===
        List<CutCandidate> candidates = collectCutCandidates(
                narrationAnalysis, speechAnalysis, audioAnalysis, wordTimings, totalDurationMs);

        log.info("[CutEngine] Collected {} raw cut candidates", candidates.size());

        // === KROK 1.5: Dodaj kandydatów z intencji usera (warstwa D) ===
        if (userEditIntent != null && userEditIntent.hasExplicitInstructions()) {
            addUserIntentCandidates(candidates, userEditIntent, narrationAnalysis,
                    wordTimings, totalDurationMs, sceneCount);
            // Re-sort after adding new candidates
            candidates.sort(Comparator.comparingInt(c -> c.timeMs));
            candidates = deduplicateCandidates(candidates);
            log.info("[CutEngine] After user intent injection: {} candidates", candidates.size());
        }

        // === KROK 2: Odsiej kandydatów łamiących film grammar ===
        candidates = applyFilmGrammar(candidates, wordTimings);

        log.info("[CutEngine] After film grammar filter: {} candidates", candidates.size());

        // === KROK 3: Scoruj kandydatów na podstawie editing intent ===
        scoreCandidates(candidates, intent, totalDurationMs);

        // === KROK 4: Wybierz finalne cięcia z uwzględnieniem min/max I limitu assetów ===
        List<JustifiedCut> cuts = selectFinalCuts(candidates, totalDurationMs, minCutMs, maxCutMs,
                intent, maxSegments);

        // === KROK 5: Waliduj minimalne pokrycie słów per segment ===
        cuts = enforceMinWordsPerSegment(cuts, wordTimings, minCutMs);

        // NOTE: propagateAssetAssignments() REMOVED — forward-fill caused wrong assets
        // (all segments in a group got the group leader's asset instead of cycling).
        // EdlGeneratorService's scene-aware mapping (Priority 2) handles non-boundary
        // segments correctly by mapping timeline position → scene → asset.

        log.info("[CutEngine] Final result: {} justified cuts (asset limit: {})", cuts.size(), maxSegments);

        return cuts;
    }

    /**
     * Backwards compat — bez availableAssetCount i sceneCount.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs) {
        return generateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs, 0, 0);
    }

    /**
     * Backwards compat — bez sceneCount.
     */
    public List<JustifiedCut> generateCuts(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            int availableAssetCount) {
        return generateCuts(narrationAnalysis, speechAnalysis, audioAnalysis,
                wordTimings, totalDurationMs, minCutMs, maxCutMs, availableAssetCount, 0);
    }

    // =========================================================================
    // KROK 1 — ZBIERANIE KANDYDATÓW
    // =========================================================================

    private List<CutCandidate> collectCutCandidates(
            NarrationAnalysis narrationAnalysis,
            SpeechTimingAnalysis speechAnalysis,
            AudioAnalysisResponse audioAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs) {

        List<CutCandidate> candidates = new ArrayList<>();

        // --- A) Kandydaci z NARRATION ANALYSIS ---
        if (narrationAnalysis.getSegments() != null && narrationAnalysis.getSegments().size() > 1) {
            addNarrationCandidates(candidates, narrationAnalysis, wordTimings);
        }

        // --- B) Kandydaci z SPEECH TIMING ---
        if (speechAnalysis != null) {
            addSpeechCandidates(candidates, speechAnalysis, wordTimings);
        }

        // --- C) Kandydaci z MUSIC ANALYSIS ---
        if (audioAnalysis != null) {
            addMusicCandidates(candidates, audioAnalysis, totalDurationMs);
        }

        // Sortuj po timestamp
        candidates.sort(Comparator.comparingInt(c -> c.timeMs));

        // Deduplikacja — merguj kandydatów blisko siebie (<100ms)
        return deduplicateCandidates(candidates);
    }

    /**
     * Kandydaci z analizy narracji — cięcia na granicach segmentów semantycznych.
     */
    private void addNarrationCandidates(
            List<CutCandidate> candidates,
            NarrationAnalysis narrationAnalysis,
            List<SubtitleService.WordTiming> wordTimings) {

        var segments = narrationAnalysis.getSegments();

        for (int i = 1; i < segments.size(); i++) {
            var prevSeg = segments.get(i - 1);
            var currSeg = segments.get(i);

            // Znajdź timestamp granicy między segmentami
            int boundaryMs = findSegmentBoundaryMs(prevSeg, currSeg, wordTimings);
            if (boundaryMs <= 0) continue;

            boolean topicChange = !Objects.equals(prevSeg.getTopic(), currSeg.getTopic());
            boolean highImportance = currSeg.getImportance() > 0.75;
            boolean isHook = "hook".equals(currSeg.getType());
            boolean isCta = "cta".equals(currSeg.getType());
            boolean isClimax = "climax".equals(currSeg.getType());

            // Klasyfikacja cięcia
            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason primaryReason;
            double score;

            if (topicChange || isHook || isClimax) {
                classification = JustifiedCut.CutClassification.HARD;
                primaryReason = topicChange ? JustifiedCut.CutReason.TOPIC_CHANGE
                        : isHook ? JustifiedCut.CutReason.HOOK_START
                        : JustifiedCut.CutReason.HIGH_IMPORTANCE;
                score = 0.9;
            } else if (highImportance || isCta) {
                classification = JustifiedCut.CutClassification.HARD;
                primaryReason = isCta ? JustifiedCut.CutReason.CTA_TRANSITION
                        : JustifiedCut.CutReason.HIGH_IMPORTANCE;
                score = 0.8;
            } else {
                classification = JustifiedCut.CutClassification.SOFT;
                primaryReason = JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 0.6;
            }

            List<JustifiedCut.CutReason> secondary = new ArrayList<>();
            if (topicChange) secondary.add(JustifiedCut.CutReason.TOPIC_CHANGE);
            if (highImportance) secondary.add(JustifiedCut.CutReason.HIGH_IMPORTANCE);

            candidates.add(new CutCandidate(
                    boundaryMs, classification, primaryReason, secondary,
                    score, i, "narration"));
        }
    }

    /**
     * Kandydaci z analizy mowy — cięcia na pauzach i granicach zdań.
     */
    private void addSpeechCandidates(
            List<CutCandidate> candidates,
            SpeechTimingAnalysis speechAnalysis,
            List<SubtitleService.WordTiming> wordTimings) {

        if (speechAnalysis.getPauses() == null) return;

        for (var pause : speechAnalysis.getPauses()) {
            // Punkt cięcia = środek pauzy (naturalne miejsce na cut)
            int cutMs = (pause.getStartMs() + pause.getEndMs()) / 2;

            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason primaryReason;
            double score;

            switch (pause.getType()) {
                case "dramatic" -> {
                    classification = JustifiedCut.CutClassification.HARD;
                    primaryReason = JustifiedCut.CutReason.DRAMATIC_PAUSE;
                    score = 0.85;
                }
                case "sentence_end" -> {
                    classification = JustifiedCut.CutClassification.SOFT;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.7;
                }
                case "enumeration" -> {
                    classification = JustifiedCut.CutClassification.MICRO;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.5;
                }
                default -> { // breath
                    classification = JustifiedCut.CutClassification.SOFT;
                    primaryReason = JustifiedCut.CutReason.SENTENCE_END_PAUSE;
                    score = 0.4;
                }
            }

            candidates.add(new CutCandidate(
                    cutMs, classification, primaryReason, List.of(),
                    score, -1, "speech"));
        }

        // Dodaj kandydatów na zmianach tempa
        if (speechAnalysis.getTempoWindows() != null && speechAnalysis.getTempoWindows().size() > 1) {
            var windows = speechAnalysis.getTempoWindows();
            for (int i = 1; i < windows.size(); i++) {
                var prev = windows.get(i - 1);
                var curr = windows.get(i);

                if (!prev.getClassification().equals(curr.getClassification())) {
                    int cutMs = curr.getStartMs();
                    boolean speedUp = "fast".equals(curr.getClassification());

                    candidates.add(new CutCandidate(
                            cutMs,
                            speedUp ? JustifiedCut.CutClassification.MICRO : JustifiedCut.CutClassification.SOFT,
                            JustifiedCut.CutReason.TEMPO_SHIFT,
                            List.of(speedUp ? JustifiedCut.CutReason.ENERGY_RISE : JustifiedCut.CutReason.ENERGY_DROP),
                            0.45, -1, "tempo"));
                }
            }
        }
    }

    /**
     * Kandydaci z analizy muzyki — cięcia na beatach, dropach, zmianach sekcji.
     */
    private void addMusicCandidates(
            List<CutCandidate> candidates,
            AudioAnalysisResponse audioAnalysis,
            int totalDurationMs) {

        // Cięcia na granicach sekcji muzycznych (drop → build, build → peak, etc.)
        if (audioAnalysis.sections() != null) {
            for (int i = 1; i < audioAnalysis.sections().size(); i++) {
                var section = audioAnalysis.sections().get(i);
                int cutMs = (int) (section.start() * 1000);

                if (cutMs >= totalDurationMs) break;

                boolean isDrop = "drop".equalsIgnoreCase(section.type()) || "peak".equalsIgnoreCase(section.type());

                candidates.add(new CutCandidate(
                        cutMs,
                        isDrop ? JustifiedCut.CutClassification.HARD : JustifiedCut.CutClassification.SOFT,
                        isDrop ? JustifiedCut.CutReason.MUSIC_DROP : JustifiedCut.CutReason.MUSIC_BEAT,
                        List.of(),
                        isDrop ? 0.75 : 0.5, -1, "music_section"));
            }
        }

        // Beaty mogą wzmocnić istniejących kandydatów (nie dodajemy beata jako samodzielnego cuta,
        // chyba że jest w sekcji high-energy)
        // Beat scoring jest w scoreCandidates()
    }

    // =========================================================================
    // WARSTWA D — USER INTENT CANDIDATES
    // =========================================================================

    /**
     * Kandydaci z intencji usera — cięcia wymuszane przez role assetów.
     *
     * Jeśli user powiedział "asset 0 = intro, asset 1 = content, asset 2 = outro",
     * to CutEngine MUSI wygenerować HARD CUT-y na granicach tych ról.
     *
     * Scoring: user-intent cuty mają NAJWYŻSZY score (1.0) bo to jawna intencja.
     * Nie mogą być odrzucone przez film grammar (chyba że wypadają w środku słowa —
     * wtedy snap do najbliższego word boundary).
     *
     * Logika:
     *   - Dzielimy timeline na segmenty proporcjonalnie do scen
     *   - Dla każdego placement z inną rolą niż "auto" → HARD CUT na granicy
     *   - intro → HARD CUT po jego zakończeniu
     *   - outro → HARD CUT przed jego rozpoczęciem
     *   - content → SOFT CUT na przejściu do następnego content
     */
    private void addUserIntentCandidates(
            List<CutCandidate> candidates,
            UserEditIntent userEditIntent,
            NarrationAnalysis narrationAnalysis,
            List<SubtitleService.WordTiming> wordTimings,
            int totalDurationMs,
            int sceneCount) {

        List<UserEditIntent.AssetPlacement> placements = userEditIntent.getPlacements();
        if (placements == null || placements.isEmpty()) return;

        int assetCount = placements.size();
        if (assetCount <= 1) return; // Single asset = no cuts needed from intent

        log.info("[CutEngine] Adding user intent candidates — {} placements", assetCount);

        // Calculate proportional boundaries based on scene count or equal division
        int effectiveSegments = sceneCount > 0 ? sceneCount : assetCount;
        int segmentDuration = totalDurationMs / effectiveSegments;

        for (int i = 0; i < placements.size() - 1; i++) {
            UserEditIntent.AssetPlacement current = placements.get(i);
            UserEditIntent.AssetPlacement next = placements.get(i + 1);

            // Skip if both are "auto"
            if ("auto".equals(current.getRole()) && "auto".equals(next.getRole())) continue;

            // Cut point = boundary between this placement and the next
            int cutMs;

            // If current has a duration hint, use it
            if (current.getDurationHintMs() > 0) {
                cutMs = calculatePlacementStartMs(placements, i) + current.getDurationHintMs();
            } else {
                // Use proportional boundary
                cutMs = (i + 1) * segmentDuration;
            }

            // Snap to word boundary if we have word timings
            if (wordTimings != null && !wordTimings.isEmpty()) {
                int snappedSentence = snapToSentenceEnd(cutMs, wordTimings);
                if (snappedSentence > 0 && Math.abs(snappedSentence - cutMs) < 3000) {
                    cutMs = snappedSentence;
                } else {
                    int snappedWord = snapToWordBoundary(cutMs, wordTimings);
                    if (snappedWord > 0) cutMs = snappedWord;
                }
            }

            // Clamp to valid range
            cutMs = Math.max(ABSOLUTE_MIN_CUT_MS, Math.min(cutMs, totalDurationMs - ABSOLUTE_MIN_CUT_MS));

            // Determine classification based on role transition
            JustifiedCut.CutClassification classification;
            JustifiedCut.CutReason reason;
            double score;

            boolean isRoleTransition = !sameRole(current.getRole(), next.getRole());
            boolean involvesIntroOutro = "intro".equals(current.getRole()) || "outro".equals(next.getRole());

            if (involvesIntroOutro || isRoleTransition) {
                classification = JustifiedCut.CutClassification.HARD;
                reason = involvesIntroOutro
                        ? JustifiedCut.CutReason.TOPIC_CHANGE
                        : JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 1.0; // Highest — user explicitly requested this
            } else {
                classification = JustifiedCut.CutClassification.SOFT;
                reason = JustifiedCut.CutReason.TOPIC_CHANGE;
                score = 0.85;
            }

            List<JustifiedCut.CutReason> secondary = new ArrayList<>();
            secondary.add(JustifiedCut.CutReason.ATTENTION_RESET);

            CutCandidate candidate = new CutCandidate(
                    cutMs, classification, reason, secondary,
                    score, i, "user_intent");
            // Tag: the segment AFTER this cut should use the next placement's asset
            candidate.assignedAssetIndex = next.getAssetIndex();
            candidates.add(candidate);

            log.debug("[CutEngine] User intent cut at {}ms — {} → {} ({}) → next asset: {}",
                    cutMs, current.getRole(), next.getRole(), classification, next.getAssetIndex());
        }

        // Tag the FIRST segment (before any cut) with the first placement's asset
        if (!placements.isEmpty()) {
            // Find or create a candidate at time 0 to tag the first asset
            CutCandidate firstCandidate = null;
            for (CutCandidate c : candidates) {
                if (c.timeMs == 0 || (c.timeMs < ABSOLUTE_MIN_CUT_MS && "user_intent".equals(c.source))) {
                    firstCandidate = c;
                    break;
                }
            }
            if (firstCandidate == null) {
                // Create a virtual candidate at time 0 to carry the first asset assignment
                firstCandidate = new CutCandidate(0,
                        JustifiedCut.CutClassification.HARD,
                        JustifiedCut.CutReason.HOOK_START,
                        List.of(), 1.0, 0, "user_intent");
                candidates.add(firstCandidate);
            }
            firstCandidate.assignedAssetIndex = placements.get(0).getAssetIndex();
        }
    }

    /**
     * Propaguje assignedAssetIndex z user-intent cuts na sąsiednie segmenty.
     *
     * Logika: segmenty między dwoma user-intent punktami cięcia powinny używać
     * tego samego assetu. Np. jeśli user chce "asset 0 jako intro, asset 1-3 jako content":
     *   - Segment 0 (start) → asset 0 (z user_intent)
     *   - Segment 1 (po user_intent cut) → asset 1 (z user_intent)
     *   - Segment 2 (pomiędzy, bez tagu) → dziedziczy asset 1
     *   - Segment 3 (po user_intent cut) → asset 2 (z user_intent)
     *   - itd.
     */
    private void propagateAssetAssignments(List<JustifiedCut> cuts, UserEditIntent userEditIntent) {
        if (cuts.isEmpty()) return;

        List<UserEditIntent.AssetPlacement> placements = userEditIntent.getPlacements();
        if (placements == null || placements.isEmpty()) return;

        // Build a map of time ranges to asset indices from placements
        int totalAssets = placements.size();

        // Forward propagation: fill gaps between tagged cuts
        int lastKnownAsset = -1;
        for (JustifiedCut cut : cuts) {
            if (cut.getAssignedAssetIndex() >= 0) {
                lastKnownAsset = cut.getAssignedAssetIndex();
            } else if (lastKnownAsset >= 0) {
                // This segment has no explicit assignment — inherit from last known
                cut.setAssignedAssetIndex(lastKnownAsset);
            }
        }

        // If the first segment wasn't tagged (unlikely but possible), back-propagate from first tagged
        if (cuts.get(0).getAssignedAssetIndex() < 0) {
            int firstTagged = -1;
            for (JustifiedCut cut : cuts) {
                if (cut.getAssignedAssetIndex() >= 0) {
                    firstTagged = cut.getAssignedAssetIndex();
                    break;
                }
            }
            if (firstTagged >= 0) {
                for (JustifiedCut cut : cuts) {
                    if (cut.getAssignedAssetIndex() < 0) {
                        cut.setAssignedAssetIndex(firstTagged);
                    } else {
                        break;
                    }
                }
            }
        }

        long tagged = cuts.stream().filter(c -> c.getAssignedAssetIndex() >= 0).count();
        log.info("[CutEngine] Asset assignment propagation: {}/{} cuts have assigned assets",
                tagged, cuts.size());
    }

    /**
     * Oblicza start ms dla danego placement na podstawie duration_hint_ms poprzedników.
     */
    private int calculatePlacementStartMs(List<UserEditIntent.AssetPlacement> placements, int index) {
        int startMs = 0;
        for (int i = 0; i < index; i++) {
            if (placements.get(i).getDurationHintMs() > 0) {
                startMs += placements.get(i).getDurationHintMs();
            }
        }
        return startMs;
    }

    private boolean sameRole(String a, String b) {
        if (a == null || b == null) return false;
        if ("auto".equals(a) || "auto".equals(b)) return true; // auto = don't care
        return a.equals(b);
    }

    /**
     * Szuka timestampa granicy między dwoma segmentami narracji.
     * Szuka w word timings słowa kończącego prevSeg.text.
     */
    private int findSegmentBoundaryMs(
            NarrationAnalysis.NarrationSegment prevSeg,
            NarrationAnalysis.NarrationSegment currSeg,
            List<SubtitleService.WordTiming> wordTimings) {

        if (wordTimings == null || wordTimings.isEmpty()) return -1;
        if (prevSeg.getText() == null || prevSeg.getText().isBlank()) return -1;

        // Wyciągnij ostatnie słowo z prevSeg
        String[] prevWords = prevSeg.getText().trim().split("\\s+");
        String lastWord = prevWords[prevWords.length - 1]
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .toLowerCase();

        // Wyciągnij pierwsze słowo z currSeg
        String firstWord = "";
        if (currSeg.getText() != null && !currSeg.getText().isBlank()) {
            String[] currWords = currSeg.getText().trim().split("\\s+");
            firstWord = currWords[0].replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
        }

        // Szukaj w word timings — od tyłu, żeby złapać poprawne wystąpienie
        for (int i = wordTimings.size() - 1; i >= 0; i--) {
            String wtWord = wordTimings.get(i).word()
                    .replaceAll("[^\\p{L}\\p{N}]", "")
                    .toLowerCase();

            if (wtWord.equals(lastWord)) {
                // Sprawdź czy następne słowo to firstWord (potwierdzenie granicy)
                if (!firstWord.isEmpty() && i + 1 < wordTimings.size()) {
                    String nextWtWord = wordTimings.get(i + 1).word()
                            .replaceAll("[^\\p{L}\\p{N}]", "")
                            .toLowerCase();
                    if (nextWtWord.equals(firstWord)) {
                        return wordTimings.get(i).endMs();
                    }
                }
                // Jeśli nie ma potwierdzenia, użyj tego słowa
                return wordTimings.get(i).endMs();
            }
        }

        return -1;
    }

    // =========================================================================
    // KROK 2 — FILM GRAMMAR
    // =========================================================================

    /**
     * Odsiew kandydatów łamiących zasady "film grammar":
     *   - NIE tnij w połowie słowa
     *   - NIE tnij w środku myśli — preferuj końce zdań i pauzy
     *   - Bonus za cięcie na interpunkcji kończącej zdanie (. ! ?)
     *   - Bonus za cięcie w pauzie między słowami
     *   - Kara za cięcie w środku płynnej mowy
     */
    private List<CutCandidate> applyFilmGrammar(
            List<CutCandidate> candidates,
            List<SubtitleService.WordTiming> wordTimings) {

        if (wordTimings == null || wordTimings.isEmpty()) return candidates;

        List<CutCandidate> filtered = new ArrayList<>();

        for (CutCandidate c : candidates) {
            // Sprawdź czy cięcie nie jest w środku słowa
            boolean midWord = false;
            for (SubtitleService.WordTiming wt : wordTimings) {
                if (c.timeMs > wt.startMs() + 50 && c.timeMs < wt.endMs() - 50) {
                    midWord = true;
                    break;
                }
            }

            if (midWord) {
                // Przesuń na koniec najbliższego słowa z interpunkcją kończącą zdanie
                int snappedSentence = snapToSentenceEnd(c.timeMs, wordTimings);
                if (snappedSentence > 0 && Math.abs(snappedSentence - c.timeMs) < 2000) {
                    c.timeMs = snappedSentence;
                    c.score *= 1.1; // BONUS za snap do końca zdania
                    filtered.add(c);
                } else {
                    // Fallback na koniec najbliższego słowa
                    int snapped = snapToWordBoundary(c.timeMs, wordTimings);
                    if (snapped > 0) {
                        c.timeMs = snapped;
                        c.score *= 0.7; // kara — nie jest na końcu zdania
                        filtered.add(c);
                    }
                }
            } else {
                // Cięcie nie jest w środku słowa — sprawdź czy jest na granicy zdania
                if (isAtSentenceEnd(c.timeMs, wordTimings)) {
                    c.score *= 1.15; // bonus za naturalną granicę zdania
                } else if (isInPause(c.timeMs, wordTimings)) {
                    c.score *= 1.05; // mały bonus za pauzę
                }
                filtered.add(c);
            }
        }

        return filtered;
    }

    /**
     * Sprawdza czy timestamp jest tuż po interpunkcji kończącej zdanie.
     */
    private boolean isAtSentenceEnd(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        for (SubtitleService.WordTiming wt : wordTimings) {
            if (Math.abs(wt.endMs() - timeMs) < 200) {
                String word = wt.word();
                if (!word.isEmpty()) {
                    char last = word.charAt(word.length() - 1);
                    if (last == '.' || last == '!' || last == '?' || last == ';') {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Sprawdza czy timestamp jest w pauzie między słowami (>300ms gap).
     */
    private boolean isInPause(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        for (int i = 0; i < wordTimings.size() - 1; i++) {
            int gapStart = wordTimings.get(i).endMs();
            int gapEnd = wordTimings.get(i + 1).startMs();
            if (gapEnd - gapStart >= 300 && timeMs >= gapStart && timeMs <= gapEnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * Przesuwa timestamp na koniec najbliższego słowa kończącego zdanie (. ! ? ;).
     * Jeśli nie ma takiego w zasięgu — zwraca -1.
     */
    private int snapToSentenceEnd(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        int closest = -1;
        int minDist = Integer.MAX_VALUE;

        for (SubtitleService.WordTiming wt : wordTimings) {
            String word = wt.word();
            if (word.isEmpty()) continue;
            char last = word.charAt(word.length() - 1);
            if (last == '.' || last == '!' || last == '?' || last == ';') {
                int dist = Math.abs(wt.endMs() - timeMs);
                if (dist < minDist) {
                    minDist = dist;
                    closest = wt.endMs();
                }
            }
        }

        return closest;
    }

    /**
     * Przesuwa timestamp na najbliższy koniec słowa.
     */
    private int snapToWordBoundary(int timeMs, List<SubtitleService.WordTiming> wordTimings) {
        int closest = -1;
        int minDist = Integer.MAX_VALUE;

        for (SubtitleService.WordTiming wt : wordTimings) {
            int dist = Math.abs(wt.endMs() - timeMs);
            if (dist < minDist) {
                minDist = dist;
                closest = wt.endMs();
            }
        }

        return closest;
    }

    // =========================================================================
    // KROK 3 — SCORING
    // =========================================================================

    /**
     * Oceniaj kandydatów w kontekście editing intent i łuku montażowego.
     */
    private void scoreCandidates(
            List<CutCandidate> candidates,
            NarrationAnalysis.EditingIntent intent,
            int totalDurationMs) {

        if (intent == null) return;

        for (CutCandidate c : candidates) {
            // Oblicz pozycję w filmie (0.0-1.0)
            double position = totalDurationMs > 0 ? (double) c.timeMs / totalDurationMs : 0.5;

            // Znajdź fazę łuku montażowego
            String density = getArcDensity(intent, position);
            c.editingPhase = getArcPhase(intent, position);

            // Modyfikuj score na podstawie gęstości w danej fazie
            double densityMultiplier = switch (density) {
                case "very_low" -> 0.5;
                case "low" -> 0.7;
                case "medium" -> 1.0;
                case "high" -> 1.3;
                case "very_high" -> 1.5;
                default -> 1.0;
            };

            c.score *= densityMultiplier;

            // Pattern-specific modyfikacje
            if (intent.getPattern() != null) {
                c.score *= getPatternMultiplier(intent.getPattern(), position);
            }
        }
    }

    private String getArcDensity(NarrationAnalysis.EditingIntent intent, double position) {
        if (intent.getArc() == null || intent.getArc().isEmpty()) return "medium";

        NarrationAnalysis.EditingArc matchedPhase = intent.getArc().get(0);
        for (var arc : intent.getArc()) {
            if (position >= arc.getStartPct()) {
                matchedPhase = arc;
            }
        }
        return matchedPhase.getDensity() != null ? matchedPhase.getDensity() : "medium";
    }

    private String getArcPhase(NarrationAnalysis.EditingIntent intent, double position) {
        if (intent.getArc() == null || intent.getArc().isEmpty()) return "middle";

        NarrationAnalysis.EditingArc matchedPhase = intent.getArc().get(0);
        for (var arc : intent.getArc()) {
            if (position >= arc.getStartPct()) {
                matchedPhase = arc;
            }
        }
        return matchedPhase.getPhase() != null ? matchedPhase.getPhase() : "middle";
    }

    /**
     * Modyfikator score na podstawie patternu montażu.
     * Np. slow_to_fast → niższe score na początku, wyższe na końcu.
     */
    private double getPatternMultiplier(String pattern, double position) {
        return switch (pattern) {
            case "slow_to_fast" -> 0.5 + position; // 0.5 na początku → 1.5 na końcu
            case "fast_to_slow" -> 1.5 - position; // 1.5 na początku → 0.5 na końcu
            case "wave" -> 0.7 + 0.6 * Math.sin(position * Math.PI * 2); // fala sinusoidalna
            case "constant_high" -> 1.3; // zawsze wysoko
            case "long_hold_then_burst" -> position < 0.7 ? 0.5 : 1.8; // długo trzymaj → burst
            case "breathing_with_pauses" -> 0.8 + 0.4 * Math.sin(position * Math.PI * 3); // oddychanie
            case "on_beat_consistent" -> 1.0; // bez modyfikacji — cięcia na beatach
            default -> 1.0;
        };
    }

    // =========================================================================
    // KROK 4 — SELEKCJA FINALNYCH CIĘĆ
    // =========================================================================

    /**
     * Wybiera finalne cięcia z listy kandydatów.
     * Zapewnia:
     *   - Brak ujęć krótszych niż minCutMs (domyślnie 1500ms+)
     *   - Brak ujęć dłuższych niż maxCutMs (wymusza cut)
     *   - Ciągłość timeline (end[i] = start[i+1])
     *   - Łączna liczba segmentów ≤ maxSegments (asset limit)
     *   - HARD CUTy mają priorytet, SOFT i MICRO filtrowane przez score
     */
    private List<JustifiedCut> selectFinalCuts(
            List<CutCandidate> candidates,
            int totalDurationMs,
            int minCutMs,
            int maxCutMs,
            NarrationAnalysis.EditingIntent intent,
            int maxSegments) {

        // Sortuj po score malejąco, potem po czasie rosnąco
        candidates.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.timeMs, b.timeMs);
        });

        // Greedy selection — wybieraj najlepszych kandydatów z zachowaniem min/max
        // User-intent candidates (with assignedAssetIndex) are ALWAYS selected first
        List<Integer> selectedTimes = new ArrayList<>();
        selectedTimes.add(0); // zawsze zaczynaj od 0

        // Phase 1: Select all user-intent candidates (have explicit asset assignments)
        for (CutCandidate c : candidates) {
            if (c.timeMs <= 0 || c.timeMs >= totalDurationMs) continue;
            if (c.assignedAssetIndex < 0) continue;
            if (selectedTimes.size() >= maxSegments) break;

            boolean tooClose = false;
            for (int existing : selectedTimes) {
                if (Math.abs(c.timeMs - existing) < ABSOLUTE_MIN_CUT_MS) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                selectedTimes.add(c.timeMs);
            }
        }

        // Phase 2: Fill remaining slots with non-intent candidates
        for (CutCandidate c : candidates) {
            if (c.timeMs <= 0 || c.timeMs >= totalDurationMs) continue;
            if (c.assignedAssetIndex >= 0) continue; // already handled

            if (selectedTimes.size() >= maxSegments) {
                log.info("[CutEngine] Segment limit reached — {} segments max", maxSegments);
                break;
            }

            boolean tooClose = false;
            for (int existing : selectedTimes) {
                if (Math.abs(c.timeMs - existing) < minCutMs) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose) {
                selectedTimes.add(c.timeMs);
            }
        }

        selectedTimes.add(totalDurationMs); // zawsze kończ na total duration
        Collections.sort(selectedTimes);

        // Sprawdź max cut constraint — wymusz cięcia jeśli ujęcie za długie
        selectedTimes = enforceMaxCut(selectedTimes, candidates, maxCutMs, minCutMs);

        // Buduj finalne JustifiedCut z wybranych punktów
        List<JustifiedCut> result = new ArrayList<>();
        Map<Integer, CutCandidate> candidateMap = new HashMap<>();
        for (CutCandidate c : candidates) {
            for (int time : selectedTimes) {
                if (Math.abs(c.timeMs - time) < 100) {
                    CutCandidate existing = candidateMap.get(time);
                    // Prefer candidates with explicit asset assignments
                    if (existing == null
                            || (c.assignedAssetIndex >= 0 && existing.assignedAssetIndex < 0)) {
                        candidateMap.put(time, c);
                    }
                    break;
                }
            }
        }

        for (int i = 0; i < selectedTimes.size() - 1; i++) {
            int startMs = selectedTimes.get(i);
            int endMs = selectedTimes.get(i + 1);

            CutCandidate source = candidateMap.get(startMs);

            JustifiedCut.Builder builder = JustifiedCut.builder()
                    .startMs(startMs)
                    .endMs(endMs);

            if (source != null) {
                builder.classification(source.classification)
                        .primaryReason(source.primaryReason)
                        .secondaryReasons(source.secondaryReasons)
                        .confidence(Math.min(1.0, source.score))
                        .narrationSegmentIndex(source.narrationSegmentIndex)
                        .editingPhase(source.editingPhase)
                        .suggestedEffect(suggestEffect(source))
                        .suggestedTransition(suggestTransition(source))
                        .assignedAssetIndex(source.assignedAssetIndex);
            } else {
                // Wymuszony cut (max duration) lub pierwszy segment
                builder.classification(i == 0 ? JustifiedCut.CutClassification.HARD
                                : JustifiedCut.CutClassification.SOFT)
                        .primaryReason(i == 0 ? JustifiedCut.CutReason.HOOK_START
                                : JustifiedCut.CutReason.DURATION_CONSTRAINT)
                        .secondaryReasons(List.of())
                        .confidence(i == 0 ? 1.0 : 0.5)
                        .narrationSegmentIndex(-1)
                        .editingPhase(source != null ? source.editingPhase : "unknown");
            }

            result.add(builder.build());
        }

        return result;
    }

    /**
     * Wymusza cięcia tam, gdzie ujęcie jest dłuższe niż maxCutMs.
     * Szuka najlepszego kandydata w tym przedziale albo wstawia cięcie w połowie.
     */
    private List<Integer> enforceMaxCut(
            List<Integer> times,
            List<CutCandidate> candidates,
            int maxCutMs,
            int minCutMs) {

        List<Integer> result = new ArrayList<>(times);
        boolean changed = true;

        while (changed) {
            changed = false;
            for (int i = 0; i < result.size() - 1; i++) {
                int duration = result.get(i + 1) - result.get(i);
                if (duration > maxCutMs) {
                    // Szukaj najlepszego kandydata w tym przedziale
                    int start = result.get(i);
                    int end = result.get(i + 1);
                    CutCandidate best = null;

                    for (CutCandidate c : candidates) {
                        if (c.timeMs > start + minCutMs && c.timeMs < end - minCutMs) {
                            if (best == null || c.score > best.score) {
                                best = c;
                            }
                        }
                    }

                    int insertMs;
                    if (best != null) {
                        insertMs = best.timeMs;
                    } else {
                        // Brak kandydata — tnij w połowie
                        insertMs = start + duration / 2;
                    }

                    result.add(i + 1, insertMs);
                    changed = true;
                    break; // restart loop
                }
            }
        }

        return result;
    }

    // =========================================================================
    // KROK 5 — MINIMALNE POKRYCIE SŁÓW PER SEGMENT
    // =========================================================================

    /**
     * Merguje segmenty, które pokrywają za mało słów (< MIN_WORDS_PER_SEGMENT).
     *
     * Problem: "jedno słowo i cut" — wygląda źle i nie ma sensu wizualnego.
     * Rozwiązanie: jeśli segment pokrywa < 3 słowa, połącz go z sąsiednim.
     *
     * Mergujemy z NASTĘPNYM segmentem (nie z poprzednim), żeby zachować
     * naturalną kontynuację myśli.
     */
    private List<JustifiedCut> enforceMinWordsPerSegment(
            List<JustifiedCut> cuts,
            List<SubtitleService.WordTiming> wordTimings,
            int minCutMs) {

        if (wordTimings == null || wordTimings.isEmpty() || cuts.size() <= 1) return cuts;

        List<JustifiedCut> result = new ArrayList<>();
        int i = 0;

        while (i < cuts.size()) {
            JustifiedCut current = cuts.get(i);

            // Policz słowa w tym segmencie
            int wordCount = countWordsInRange(wordTimings, current.getStartMs(), current.getEndMs());

            // Czy segment jest za krótki (za mało słów LUB za krótki czas)?
            boolean tooFewWords = wordCount < MIN_WORDS_PER_SEGMENT;
            boolean tooShortDuration = (current.getEndMs() - current.getStartMs()) < minCutMs;

            if ((tooFewWords || tooShortDuration) && i + 1 < cuts.size()) {
                // Merguj z następnym segmentem
                JustifiedCut next = cuts.get(i + 1);

                // Preserve assignedAssetIndex: prefer current's, fall back to next's
                int mergedAssetIdx = current.getAssignedAssetIndex() >= 0
                        ? current.getAssignedAssetIndex()
                        : next.getAssignedAssetIndex();

                JustifiedCut merged = JustifiedCut.builder()
                        .startMs(current.getStartMs())
                        .endMs(next.getEndMs())
                        // Zachowaj silniejsze uzasadnienie
                        .classification(current.getConfidence() >= next.getConfidence()
                                ? current.getClassification() : next.getClassification())
                        .primaryReason(current.getConfidence() >= next.getConfidence()
                                ? current.getPrimaryReason() : next.getPrimaryReason())
                        .secondaryReasons(current.getSecondaryReasons() != null
                                ? current.getSecondaryReasons() : List.of())
                        .confidence(Math.max(current.getConfidence(), next.getConfidence()))
                        .narrationSegmentIndex(current.getNarrationSegmentIndex())
                        .editingPhase(current.getEditingPhase())
                        .suggestedEffect(current.getSuggestedEffect())
                        .suggestedTransition(next.getSuggestedTransition())
                        .assignedAssetIndex(mergedAssetIdx)
                        .build();

                // Zamień next w liście (aby kolejna iteracja mogła sprawdzić merged)
                if (i + 1 < cuts.size()) {
                    cuts.set(i + 1, merged);
                }
                i++; // pomiń current, merged jest na pozycji i+1

                log.debug("[CutEngine] Merged segment {}ms-{}ms ({} words) with next → {}ms-{}ms",
                        current.getStartMs(), current.getEndMs(), wordCount,
                        merged.getStartMs(), merged.getEndMs());
            } else {
                result.add(current);
                i++;
            }
        }

        if (result.size() < cuts.size()) {
            log.info("[CutEngine] Merged {} too-short segments → {} final segments",
                    cuts.size() - result.size(), result.size());
        }

        return result;
    }

    /**
     * Liczy ile słów z WhisperX mieści się w przedziale [startMs, endMs].
     */
    private int countWordsInRange(List<SubtitleService.WordTiming> words, int startMs, int endMs) {
        int count = 0;
        for (SubtitleService.WordTiming wt : words) {
            int wordCenter = (wt.startMs() + wt.endMs()) / 2;
            if (wordCenter >= startMs && wordCenter < endMs) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // DEDUPLICATION
    // =========================================================================

    private List<CutCandidate> deduplicateCandidates(List<CutCandidate> sorted) {
        if (sorted.isEmpty()) return sorted;

        List<CutCandidate> result = new ArrayList<>();
        result.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            CutCandidate prev = result.get(result.size() - 1);
            CutCandidate curr = sorted.get(i);

            if (Math.abs(curr.timeMs - prev.timeMs) < 100) {
                // Merguj — zachowaj tego z wyższym score
                if (curr.score > prev.score) {
                    // Przenieś secondary reasons z prev do curr
                    List<JustifiedCut.CutReason> merged = new ArrayList<>(curr.secondaryReasons);
                    merged.add(prev.primaryReason);
                    curr.secondaryReasons = merged;
                    curr.score = Math.max(curr.score, prev.score) * 1.1; // bonus za nakładanie się
                    result.set(result.size() - 1, curr);
                } else {
                    List<JustifiedCut.CutReason> merged = new ArrayList<>(prev.secondaryReasons);
                    merged.add(curr.primaryReason);
                    prev.secondaryReasons = merged;
                    prev.score *= 1.1; // bonus
                }
            } else {
                result.add(curr);
            }
        }

        return result;
    }

    // =========================================================================
    // SUGGESTIONS — co wizualnie zrobić na danym cięciu
    // =========================================================================

    private String suggestEffect(CutCandidate c) {
        return switch (c.classification) {
            case HARD -> switch (c.primaryReason) {
                case TOPIC_CHANGE, HOOK_START -> "fast_zoom";
                case MUSIC_DROP -> "shake";
                case HIGH_IMPORTANCE, CTA_TRANSITION -> "zoom_in";
                default -> "zoom_in";
            };
            case SOFT -> switch (c.primaryReason) {
                case SENTENCE_END_PAUSE -> "drift";
                case ENERGY_DROP -> "zoom_out";
                case DRAMATIC_PAUSE -> "pan_left";
                default -> "drift";
            };
            case MICRO -> "fast_zoom";
        };
    }

    private String suggestTransition(CutCandidate c) {
        return switch (c.classification) {
            case HARD -> "cut";
            case SOFT -> "fade";
            case MICRO -> "cut";
        };
    }

    // =========================================================================
    // INTERNAL MODEL
    // =========================================================================

    /**
     * Wewnętrzna reprezentacja kandydata na cięcie — przed finalną selekcją.
     */
    private static class CutCandidate {
        int timeMs;
        JustifiedCut.CutClassification classification;
        JustifiedCut.CutReason primaryReason;
        List<JustifiedCut.CutReason> secondaryReasons;
        double score;
        int narrationSegmentIndex;
        String source; // "narration", "speech", "music_section", "tempo", "user_intent"
        String editingPhase;
        int assignedAssetIndex = -1; // -1 = auto, >= 0 = explicit user assignment

        CutCandidate(int timeMs, JustifiedCut.CutClassification classification,
                     JustifiedCut.CutReason primaryReason,
                     List<JustifiedCut.CutReason> secondaryReasons,
                     double score, int narrationSegmentIndex, String source) {
            this.timeMs = timeMs;
            this.classification = classification;
            this.primaryReason = primaryReason;
            this.secondaryReasons = new ArrayList<>(secondaryReasons);
            this.score = score;
            this.narrationSegmentIndex = narrationSegmentIndex;
            this.source = source;
        }
    }
}

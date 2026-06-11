# BossAI — Improvement Plan (Audit + Roadmap)

Date: 2026-06-11
Audited: Spring Boot backend (`master`/current), Remotion renderer (`remotion-branch`), audio analysis service (`audio-analys-service`).

Goal restated:
1. Users render TikTok videos from **only the assets they provide**.
2. They then refine the result in the **timeline editor**.
3. The app must edit like a **human editor** — decisions driven by content and the chosen style, not a copy-pasted template. Today only the pain→payoff style exists.
4. **Quality over quantity** — fewer features, executed excellently.

---

## 1. Audit summary — where the product actually is

| Area | Verdict | Notes |
|---|---|---|
| Asset-only rendering | **Already works mechanically** | Custom assets short-circuit AI generation (`ImageStep.java:79–91`, `VoiceStep`, `VideoStep`). Blank prompt + assets auto-applies PROBLEM_PAYOFF (`GenerationServiceImpl.java:490–495`). The gap is output *quality* for arbitrary user footage, not plumbing. |
| Cutting ("when to cut") | **Genuinely adaptive — keep** | CutEngine combines narration semantics (GPT), speech pauses/sentence ends (WhisperX), music drops, film grammar (never cut mid-word), user intent. Cut points are unique per video. |
| Effects/transitions ("what to do at the cut") | **Template — this is the copy-paste feeling** | Effects are assigned round-robin from JSON beat patterns: `EdlGeneratorService.java:2644–2648` (`patterns.get(usage % patterns.size())`). Zero awareness of what's in the shot or what the narration is doing at that moment, despite AssetProfile + NarrationAnalysis already being computed upstream. |
| Music dynamics | **Template + dead end** | Fixed linear ramp per beat from `problem_payoff.json` (`A: 0.06 → F: 0.22`). `volume_by_beat` exists in `EdlAudioTrack` but appears **nowhere** in Remotion src — never rendered. No ducking under voiceover. The audio service's energy curve/sections are used for cut candidates but not for dynamics. |
| Style system | **One real style; 7 ghost enums** | Only `PROBLEM_PAYOFF` has a JSON preset. `BEFORE_AFTER`, `TUTORIAL`, etc. are enum values that would fail at runtime. `AutonomousCompositionDecider.java:57–71` hard-exits for any preset other than PROBLEM_PAYOFF. |
| Timeline editor backend | **~85% done** | GET/PUT `/api/v1/projects/{id}/timeline`, EDL versioning (AI_GENERATED vs USER_MODIFIED), version history, render trigger + status. Re-render correctly skips the AI pipeline. Missing: validation hardening, editable subtitles, progress streaming. |
| Remotion renderer | **Effects are good; mixing/framing weak** | 23 effects with proper easing, professional karaoke subtitles, beat-sync, multi-layer composition. Gaps: no audio ducking/crossfade, non-9:16 assets silently center-cropped, no title-safe margins, no auto-motion on stills, `trim_out_ms` and `speed_ramp` silently ignored, GIFs assume square. |
| Audio analysis service | **Solid** | `/api/v1/analyze-audio` (BPM, beats, onsets, energy curve @0.5s, sections intro/drop/chorus, mood) + `/api/v1/align` (WhisperX word timing <20ms). The raw signals for "edit like a human" exist — the pipeline just doesn't consume them for dynamics. |

**Stale docs:** CLAUDE.md's TODO is outdated — Phase 2 endpoints (`/analyze-prompt`, `/assign-assets` in `GenerationController.java:58,78`) and Phase 3 timeline endpoints already exist. Update CLAUDE.md once this plan lands.

---

## 2. The core insight

The user-perceived problem ("it copies a style instead of editing like a human") has a **precise technical location**. The pipeline is roughly half adaptive, half template:

**Adaptive (keep, don't touch):**
- Cut placement — CutEngine layers narration/speech/music/user-intent signals.
- Scene↔asset 1:1 mapping (CRITICAL invariant — `media.size()` == scene count, never override).
- Overlay placement — GPT Vision + semantic matching.

**Template (this is what to fix):**
1. Effect/transition choice: round-robin rotation through JSON patterns — `EdlGeneratorService.java:2626–2690`.
2. Music volume: fixed per-beat constants from JSON, never content-driven, and the per-beat field isn't even rendered.
3. Text overlays: 3 fixed templates (HOOK/RESULT/CTA) with hardcoded timing/styling.
4. Color grade: fixed per beat.

A human editor asks: *what is in this shot, what is the narration doing right now, what is the music doing, and what does the style call for?* All four signals are already computed (AssetProfile, NarrationAnalysis, MusicAnalysis, DnaPreset). They just don't meet at the decision point. That meeting point is the work.

---

## 3. The plan

Ordering rationale: Phase A is small and lifts **every** video immediately (including editor re-renders). Phase B is the heart of "human editor". Phase C makes the edit loop shippable. Phase D only after one style is excellent.

### Phase A — Render quality floor (Remotion, small + high ROI)

> **STATUS: DONE** — branch `claude/dazzling-shannon-hd6eeu-remotion` (based on `remotion-branch`), commit `bcd5a59`.
> Delivered: auto-ducking (+ mix_config), blur-fill auto-framing, title-safe areas, auto Ken Burns on stills,
> trim_out_ms + speed_ramp implemented, rgb_split/grain_overlay schema fix, OffthreadVideo switch,
> edge-reveal fixes in Pan/Drift/KenBurns (all three slid black bands into frame — found during verification).
> GIF aspect was a false alarm (fit="contain" already preserves it). 72 tests green; verified with a real
> end-to-end smoke render (`scripts/smoke-render.ts`), ducking measured at 0.448× vs configured 0.45.
> Backend follow-up for Phase B: EDL may now emit `framing` per segment and `mix_config`/`ducking` knobs.

> No new effects. Fix the things that make output look amateur regardless of editing decisions.

- **A1. Audio ducking + eased fades** — `src/components/AudioTrackComponent.tsx`
  - Auto-duck music under active voiceover (use `whisper_words` presence per frame, ~−8 dB with ~150ms eased ramps). Config flag in `subtitle_config`-style EDL field so backend can tune.
  - Replace linear fades with eased curves; crossfade when music track ends before video end.
- **A2. Framing for non-9:16 assets** — `src/components/VideoSegment.tsx:388,396`
  - TikTok-standard blurred-fill: scaled blurred copy behind, `objectFit: contain` in front, instead of silent center-crop. Add `framing: "cover" | "blur_fill"` to the segment schema (default `blur_fill` for landscape sources; backend decides via asset dimensions which it already stores).
- **A3. Title-safe margins** — `TextOverlay.tsx`, `GifOverlayComponent.tsx`, `SubtitleTrack.tsx`
  - Enforce ~5% safe area; clamp positions.
- **A4. Auto-motion on stills** — `TikTokVideo.tsx` / `VideoSegment.tsx`
  - IMAGE segments with no effects get a default subtle Ken Burns so image holds are never dead frames.
- **A5. Contract honesty**
  - Implement `trim_out_ms` (video + audio). Implement `speed_ramp` (interpolated `playbackRate`) **or** remove it from schema + EffectRegistry — no silently ignored fields. Fix GIF aspect ratio (`GifOverlayComponent.tsx:34`).

Acceptance: a video from 3 landscape clips + 1 photo + voiceover + music has no cropped heads, no dead stills, music audibly dips under voice, text never clipped on a phone.

### Phase B — The editor brain (Spring backend — the core of "human editor")

> **STATUS: DONE** — backend commit `a106c9c` (this branch) + renderer commit `d408f6a` (`claude/dazzling-shannon-hd6eeu-remotion`).
> Delivered: EffectDirector (deterministic content-aware scoring: narration type/energy/importance ×
> asset profile × music energy/on-beat × style grammar × variety/restraint rules, per-segment justification
> logs); problem_payoff.json restructured from fixed scene_patterns into a style grammar (palette + weights +
> intensity ranges + content prefs — legacy presets fall back to round-robin); MusicDynamicsPlanner emits
> `volume_points` (style curve × real music energy, beat-boundary ramps, drop swells) consumed by the renderer
> (replaces dead `volume_by_beat`); B4 minimal: continuous source timecode for re-used VIDEO assets
> (no more restart-at-0:00). Scene-count/1:1 invariant untouched. 16 backend unit tests (incl. against the
> real preset JSON) + 5 renderer tests; full-suite green except the pre-existing `BossAiApplicationTests`
> context test (needs a DB — environmental, fails identically without these changes).

- **B1. EffectDirector: content-aware effect/transition selection** (replaces round-robin)
  - New `service/director/EffectDirector.java`, called from `EdlGeneratorService.applyDnaToEdl()` instead of the rotation at lines 2644–2648.
  - Inputs per segment: AssetProfile (person/product/scenery, motion, mood), narration segment (type hook/emphasis/climax/cta, energy, importance), music context (section type, local energy, beat proximity), DNA beat role, previous segment's choice (variety + rhythm constraint).
  - Deterministic rules first (e.g., *talking head + emphasis → punch-in `zoom_in_offset` toward face; product still + reveal → `ken_burns` or `color_pop`; climax on music drop → `smash_zoom`/`whip_pan` synced to beat; calm setup → no effect — restraint is a decision*). GPT only if rules prove insufficient.
  - Each decision carries a `justification` string (logged; later surfaced in editor UI).
- **B2. DNA preset becomes a style grammar, not a fixed pattern list**
  - Restructure `dna-presets/problem_payoff.json`: per beat → *allowed* effect palette with weights + selection hints + intensity ranges + pacing bounds + audio dynamics profile, instead of an ordered pattern list. `DnaPresetConfig` updated accordingly; EffectDirector selects *within* the grammar. The style says **what's allowed and what it should feel like**; the director decides **what actually happens** based on content.
- **B3. Content-driven music dynamics**
  - Compute volume automation from voice activity + music energy curve + DNA dynamics profile. Add `volume_points: [{ms, volume}]` to `EdlAudioTrack` + Remotion `AudioTrack` schema (supersedes never-rendered `volume_by_beat`). Renderer interpolates between points; A1 ducking remains the baseline safety net.
- **B4. Source-footage in-point selection** (stretch)
  - Verify how `trim_in_ms` is chosen for long user videos today (suspected: always 0). A human picks the best moment in the clip. Minimum viable: scene/motion heuristic via ffmpeg or asset-analysis hints to choose in-points. Verify first; do not assume.
- **Invariant (do not break):** scene count == `media.size()`, 1:1 order-preserving mapping. EffectDirector changes decoration of segments, never their count or asset assignment.

Acceptance: same 5 assets rendered twice with different narrations produce visibly different effect/dynamics decisions; logs show per-segment justifications; no two adjacent segments get identical effect+transition unless justified.

### Phase C — Editor hardening (ship the edit → re-render loop)

> **STATUS: DONE (C1–C3)** — commit on this branch after Phase B.
> Delivered: EdlValidator strict/lenient split with structured issues (scope/index/field/message) —
> strict PUT /timeline rejects unknown asset_ids, out-of-range trims, segments playing past trimmed
> source, >500ms primary-layer gaps, untransitioned overlaps, unknown effects, broken whisper words;
> structured 400 body { message, errors[], warnings[] } via EdlValidationException + handler;
> whisper_words validated as user-editable (C2); GET /api/v1/projects/{id}/render/progress SSE
> (RenderProgressService + RemotionRenderClient progress callback + orchestrator broadcasts) (C3).
> C4 (frontend wiring: version-restore picker, draft-save toggle, asset swap UI) remains — backend ready.

- **C1. Validation that fails fast, not at render** — `service/edl/EdlValidator.java`
  - Reject unknown `asset_id` (exists in ProjectAsset + owned by project); reject `trim_in_ms/trim_out_ms` outside asset duration; gaps >500ms and same-layer overlaps without transition become errors, not warnings.
  - Structured error response: `{segments: [{index, field, error}], audio_tracks: [...]}` instead of concatenated strings.
- **C2. Editable subtitles** — allow `whisper_words` edits via PUT timeline (validate timings within voice duration, non-overlapping per word); editor can fix typos and timing.
- **C3. Render progress streaming** — wire `RenderJobService` progress into the existing SSE `ProgressController` pattern so the editor shows live render progress instead of 2s polling.
- **C4. Frontend wiring (backend already ready)** — version restore picker (GET `/versions`, `/versions/{v}`), draft save toggle (`?triggerRender=false`), asset-swap on segment (PUT already accepts it once C1 validates it).

### Phase D — Style system generalization (only after PROBLEM_PAYOFF is excellent)

- **D1.** Remove the PROBLEM_PAYOFF hard-gate in `AutonomousCompositionDecider.java:66–71`; composition rules read from the style grammar.
- **D2.** A new style = one grammar JSON + one prompt file, zero Java changes (B2 makes this true).
- **D3.** Ship a second style end-to-end as proof (suggest `BEFORE_AFTER` — closest to existing footage patterns).
- **D4.** Until then, trim `DnaPreset` enum to implemented values or fail gracefully with a clear API error.

### Explicitly NOT doing (quality over quantity)

- No new effects in Remotion (23 well-made ones is plenty).
- No new styles before Phase D.
- No partial re-render, no multi-user editing, no collaborative timeline — full re-render per save is fine for now.

---

## 4. Refined session prompts (use these to kick off each coding session)

**Phase A:** "On `remotion-branch`: raise the render quality floor. 1) Auto-duck music under voiceover with eased ramps and replace linear audio fades (AudioTrackComponent.tsx). 2) Blurred-fill framing for non-9:16 segments instead of silent center-crop (VideoSegment.tsx), schema field `framing`. 3) Enforce 5% title-safe margins for text overlays, GIFs, subtitles. 4) Default subtle Ken Burns on IMAGE segments without effects. 5) Implement `trim_out_ms` and `speed_ramp` (or delete them from the schema), fix GIF aspect ratio. No new effects. Acceptance: landscape clips + a photo + voice + music render with no cropped heads, no dead stills, audible ducking, no clipped text."

**Phase B:** "On the backend: replace round-robin effect assignment (`EdlGeneratorService.java:2644–2648`) with a content-aware `EffectDirector` that picks effect/transition/intensity per segment from AssetProfile + narration segment + music section + DNA beat role, with a logged justification per decision. Restructure `problem_payoff.json` from fixed scene_patterns into a style grammar (allowed palette + weights + intensity ranges + audio dynamics profile per beat). Compute music `volume_points` from voice activity + energy curve and render them in Remotion (replaces dead `volume_by_beat`). CRITICAL: scene count must stay == media.size(), 1:1 mapping untouched."

**Phase C:** "Harden the timeline editor loop: EdlValidator must reject unknown asset_ids and out-of-range trims with structured per-segment errors; make whisper_words editable with timing validation; stream render progress over SSE via the existing ProgressController pattern."

**Phase D:** "Generalize the style system: remove the PROBLEM_PAYOFF hardcode in AutonomousCompositionDecider, make styles pure data (grammar JSON + prompt file), ship BEFORE_AFTER as the second style end-to-end, and make unimplemented presets fail gracefully."

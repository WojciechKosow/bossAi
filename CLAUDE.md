# BossAI — Video Production Pipeline

## Project Overview
SaaS app for creating short-form videos (TikTok/Reels). Spring Boot / Java 21 backend.
User provides prompt + media assets → pipeline generates a fully edited video.

**Pipeline order:** ScriptStep → ImageStep → VoiceStep+VideoStep → MusicStep → RenderStep
**Orchestration:** VideoProductionOrchestrator (AssetBridge → AudioAnalysis → IntentParsing → NarrationAnalysis → EditDna → CutEngine → EdlGeneration → Validation → Render)

**Branch:** `claude/optimize-video-cutting-aFW3d`

## Critical Rule — DO NOT BREAK
Scene count MUST always equal `media.size()` (number of user-uploaded assets).
1:1 mapping: scene 0 = asset 0, scene 1 = asset 1, etc.
This was broken before and took multiple sessions to fix. Never override scene count with anything else.

## What Already Works
- [x] Full pipeline: prompt → script → images → voice + video → music → render
- [x] 1:1 scene-to-asset mapping (user uploads 13 assets → 13 scenes in correct order)
- [x] CutEngine with justified cuts, NarrationAnalyzer, EditDna
- [x] AssetAnalyzer (visual profiles), UserIntentParser (placements, pacing, roles)
- [x] Prompt-driven scene control — user describes scenes in prompt (scene_description + mood per asset)
- [x] Music reuse by ID — `musicAssetId` field in TikTokAdRequest
- [x] Music upload as file — `musicFile` in TikTokAdRequest
- [x] Beat-aligned editing (MusicAnalysisService + MusicAlignmentService)

## What Is Ready But Inactive (groundwork files exist, not wired in)
- SceneDirective.java — per-scene layer composition model (background/primary/overlay)
- LayerAssetGenerator.java — generates FalAI images for source=generate layers
- UserEditIntent.sceneDirectives — field exists, UserIntentParser doesn't populate it yet
- ProjectAsset.prompt — field for AI-generated layer asset prompts
- SceneAsset.layerAssetIds — map layerIndex → ProjectAsset UUID

---

## TODO — Next Session

### Phase 1: Multi-Layer Scene Composition
User can describe scenes with multiple layers (e.g., "w tle video z gielda, na srodku filmik z blondynka").

**Tasks:**
- [ ] 1.1 Update UserIntentParser to detect multi-layer descriptions in prompt
  - Populate `sceneDirectives` when user describes backgrounds/overlays
  - CRITICAL: Do NOT change scene count — keep `media.size()` always
  - Keep the prompt simple, avoid the "two-level" confusion that broke things before
  - File: `service/director/UserIntentParser.java`
- [ ] 1.2 Wire LayerAssetGenerator back into VideoProductionOrchestrator
  - Call `layerAssetGenerator.generateLayerAssets()` for scenes that need generated layers
  - Map results to `SceneAsset.layerAssetIds`
  - File: `service/edl/VideoProductionOrchestrator.java`
- [ ] 1.3 Update EdlGeneratorService for multi-layer EDL output
  - Emit additional layers in EDL segments (layer > 0)
  - Pass layer info to GPT prompt
  - File: `service/edl/EdlGeneratorService.java`
- [ ] 1.4 Verify Remotion renderer handles layered composition
  - Background layer + primary layer + overlay rendering
  - Check: `remotion/` directory for composition support

### Phase 2: Interactive Asset Assignment Flow (Frontend + Backend)
App analyzes prompt → proposes scene breakdown → user visually assigns assets to scenes.

**Tasks:**
- [ ] 2.1 Create endpoint: POST /api/v1/generation/analyze-prompt
  - Takes prompt → runs UserIntentParser + ScriptStep (dry run)
  - Returns proposed scene breakdown with descriptions
  - User sees scenes and can assign assets visually
- [ ] 2.2 Create endpoint: POST /api/v1/generation/assign-assets
  - User sends scene→asset mapping (can provide more assets than scenes)
  - Backend validates and stores assignments
  - If user provides extras, system picks best match per scene
- [ ] 2.3 Update GenerationContext to accept pre-assigned scene→asset mapping
  - Skip auto-assignment when user has explicitly mapped everything
- [ ] 2.4 Frontend: Scene assignment UI
  - Display proposed scenes with descriptions
  - Drag-and-drop assets to scenes
  - Allow uploading more assets than needed

### Phase 3: Post-Render Timeline Editor
After render, user sees a timeline and can adjust what they don't like.

**Tasks:**
- [ ] 3.1 Create endpoint: GET /api/v1/generation/{id}/timeline
  - Returns EdlDto with all segments, assets, effects, transitions
  - Frontend renders this as an interactive timeline
- [ ] 3.2 Create endpoint: PUT /api/v1/generation/{id}/timeline
  - User modifies segment order, asset assignments, effects, transitions
  - Backend validates and triggers re-render
- [ ] 3.3 Frontend: Timeline UI component
  - Visual timeline with segments, drag to reorder
  - Click segment to change asset/effect/transition
  - Preview changes before re-render

### Phase 4: Effects System
User controls effects and transitions per scene via prompt or timeline.

**Tasks:**
- [ ] 4.1 Add effect/transition hints to AssetPlacement
  - `suggestedEffect`, `suggestedTransition` fields
- [ ] 4.2 Update UserIntentParser to detect effect instructions
  - "zoom na produkt", "fade to black", "glitch effect" → per-scene effect hints
- [ ] 4.3 Feed effect hints through ScriptStep → EdlGeneratorService
- [ ] 4.4 Expand effect palette in Remotion renderer

### Phase 5: Music Behavior Control
User controls music dynamics per scene.

**Tasks:**
- [ ] 5.1 Add music hints to AssetPlacement (e.g., "music_energy": "high"/"low"/"drop")
- [ ] 5.2 Update MusicAlignmentService to respect per-scene music directions
- [ ] 5.3 Dynamic volume/energy control in rendered output

---

## Key File Map
```
service/director/
  UserEditIntent.java        — Parsed editing instructions (placements, scene descriptions, mood)
  UserIntentParser.java      — Parses user prompt → UserEditIntent via GPT
  CutEngine.java             — Generates justified cuts with asset assignments
  NarrationAnalyzer.java     — Semantic narration segmentation
  SceneDirective.java        — Per-scene layer composition model (INACTIVE)
  LayerAssetGenerator.java   — Generates FalAI images for layers (INACTIVE)
  AssetProfile.java          — Visual analysis result per asset

service/edl/
  EdlGeneratorService.java   — Generates EDL (GPT-driven + deterministic fallback)
  VideoProductionOrchestrator.java — Full pipeline orchestration

service/generation/step/
  ScriptStep.java            — Generates script with content-type aware prompts
  MusicStep.java             — Loads, analyzes, aligns music

service/generation/
  GenerationContext.java     — Pipeline state container
  context/SceneAsset.java    — Per-scene data (imagePrompt, duration, layerAssetIds)

request/
  TikTokAdRequest.java       — API request (prompt, assetIds, musicAssetId, musicFile, style)

entity/
  Asset.java                 — Asset entity (orderIndex, type, storageKey, prompt)
  ProjectAsset.java          — Per-project asset (with prompt field for generated layers)
```

## Pre-existing Compilation Errors (not ours)
- `MultipartConfig.java` — missing `MultipartConfigFactory` import
- `AuthController.java` — missing `EmailChangeConfirmationRequest` class
These are unrelated to our work and exist on all branches.

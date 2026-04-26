# Frontend Integration Handoff — TikTok Video Pipeline

> **Use this as the seed prompt for a new Claude Code session in the frontend repo.**
> Backend is on branch `claude/implement-claude-tasks-70qvY` of `WojciechKosow/bossAi`.

## Context

BossAI is a SaaS for generating short-form videos (TikTok / Reels) from a prompt + uploaded media.
The backend pipeline takes `prompt + assetIds` and produces a fully edited 1080×1920 video:
**ScriptStep → ImageStep → VoiceStep+VideoStep → MusicStep → RenderStep**, then the orchestrator
runs **AssetBridge → AudioAnalysis → IntentParsing → NarrationAnalysis → EditDna → CutEngine →
EdlGeneration → Validation → Render**.

## What changed

The frontend was previously wired to a **Cloudflare AI image-generation endpoint** (single image
out of a prompt). That path is obsolete. The frontend must now drive the **TikTok video pipeline**
with the new endpoints below.

## Out of scope (DO NOT touch)

- **Auth** — already wired and working. Don't refactor login/register/session refresh.
- **Visual styling, layout, colors, copy** — handled by the user. Build functional UI; styling polish comes after.

## Goals for this session (frontend)

Replace the old single-image generation flow with three coherent screens:

1. **Create video** — prompt + asset upload + style → preview scenes → assign assets → submit.
2. **Library** — list user's projects with status (rendering / ready) and rendered video preview.
3. **Timeline editor** — DaVinci/CapCut-style editor over the EDL, segments draggable, asset/effect/transition swappable, hit "Save & Re-render" → backend re-renders.

---

## End-to-end flow

```
[Create screen]
   ├─ user types prompt, picks style, uploads N assets
   │  → POST /api/assets/upload (per file, sets orderIndex)
   │
   ├─ user clicks "Analyze" (or auto on form submit)
   │  → POST /api/generations/analyze-prompt
   │  ← PromptAnalysisResponse { scenes[], userIntent, availableAssets[] }
   │
   ├─ FE shows preview: N scenes with imagePrompt/subtitle/duration,
   │  default mapping = scene.suggestedAssetId, allow drag-drop reassign
   │
   ├─ user clicks "Generate"
   │  → POST /api/generations/assign-assets
   │     body = TikTokAdRequest { prompt, style, customMediaAssetIds, sceneAssignments[], ... }
   │  ← GenerationResponse { generationId, status: PENDING }
   │
   ├─ open SSE: GET /api/generations/{generationId}/progress
   │  ← stream of { step, percent, message }
   │
   └─ when DONE: GET /api/generations/{generationId} → finds projectId via VideoProject lookup
      (or use POST /assign-assets response to keep the generationId, then poll
       VideoProjectController list to find the project tied to that generation)

[Library screen]
   GET /api/v1/projects                    → VideoProjectDTO[]
   GET /api/v1/projects/{id}               → single project
   GET /api/v1/projects/{id}/render/status → RenderJobDTO (poll until COMPLETE)
   RenderJobDTO.outputUrl is the final video URL.

[Timeline editor screen]
   GET /api/v1/projects/{id}/timeline/edl  → EdlDto (typed)
   GET /api/v1/projects/{id}/assets        → ProjectAssetDTO[] (asset library for the project)
   user edits segments / effects / transitions in-app
   → PUT /api/v1/projects/{id}/timeline?triggerRender=true  body = EdlDto
   ← EdlVersionDTO { version, source: USER_MODIFIED }
   poll /render/status until COMPLETE → swap outputUrl
```

---

## API reference

All endpoints below require `Authorization: Bearer <jwt>` (auth already wired).
Base URL configured in env (e.g. `VITE_API_BASE`).

### Assets

#### `POST /api/assets/upload` — multipart
Form fields:
- `type` — one of `IMAGE`, `VIDEO`, `MUSIC`, `VOICE`
- `file` — the binary
- `orderIndex` — optional integer, drives default scene order

Returns `AssetDTO { id: UUID, type, originalFilename, orderIndex, ... }`.

#### `GET /api/assets`
Returns `AssetDTO[]` for the current user.

#### `DELETE /api/assets/{id}`

#### `GET /api/assets/file/{id}`
Streams the binary (for previews in the upload widget).

### Generation flow

#### `POST /api/generations/analyze-prompt`
**Stateless preview**. No DB writes, runs UserIntentParser + ScriptStep dry-run.

Body:
```json
{
  "prompt": "string (10..2000 chars)",
  "style": "STORY_MODE | HIGH_CONVERTING_AD | EDUCATIONAL | VIRAL_EDIT | UGC_STYLE | LUXURY_AD | CINEMATIC | PRODUCT_SHOWCASE | CUSTOM | null",
  "customMediaAssetIds": ["uuid", ...],
  "analyzeAssets": false
}
```

Response:
```json
{
  "contentType": "STORY",
  "hook": "...",
  "callToAction": "...",
  "totalDurationMs": 28500,
  "scenes": [
    {
      "index": 0,
      "imagePrompt": "...",
      "motionPrompt": "...",
      "subtitleText": "...",
      "durationMs": 5500,
      "suggestedRole": "intro|content|outro|cta|...",
      "suggestedMood": "calm|energetic|...",
      "sceneDirection": "...",
      "suggestedAssetId": "uuid",
      "layers": [
        { "layerIndex": 0, "role": "background", "source": "generate", "generationPrompt": "..." },
        { "layerIndex": 1, "role": "primary", "source": "provided", "assetId": "uuid" }
      ]
    }
  ],
  "userIntent": {
    "overallGoal": "...",
    "pacingPreference": "auto|fast|moderate|slow",
    "editingStyle": "cinematic|...|null",
    "structureHints": ["intro first", "end with CTA"],
    "userControlsOrder": true,
    "hasExplicitInstructions": true
  },
  "availableAssets": [
    { "id": "uuid", "type": "IMAGE|VIDEO", "originalFilename": "...", "orderIndex": 0, "description": "..." }
  ]
}
```

> ⚠ Each call costs ~2 GPT calls. Cache the response in FE state. Don't re-fire on minor input changes — debounce or require explicit "Analyze".

#### `POST /api/generations/assign-assets` — also accepts `/api/generations/tiktok-ad` (legacy alias)
Starts the actual pipeline.

Body (`TikTokAdRequest`):
```json
{
  "prompt": "string",
  "style": "VideoStyle | null",
  "assetIds": ["uuid", ...],
  "musicAssetId": "uuid|null",
  "customMediaAssetIds": ["uuid", ...],
  "customTtsAssetIds": ["uuid", ...],
  "useGptOrdering": false,
  "reuseAssets": true,
  "sceneAssignments": [
    { "sceneIndex": 0, "assetId": "uuid" },
    { "sceneIndex": 1, "assetId": "uuid" }
  ]
}
```

`sceneAssignments` is the explicit scene→asset map. Backend validates:
- `sceneIndex` ∈ [0, customMediaAssetIds.size())
- assetId must be in `customMediaAssetIds`
- no duplicate sceneIndex / assetId
- partial mappings OK — empty slots are filled with leftover assets in `orderIndex` order

Response:
```json
{ "generationId": "uuid", "status": "PENDING" }
```

#### `GET /api/generations/{id}/progress` — SSE stream
```js
const es = new EventSource(`/api/generations/${id}/progress`);
es.addEventListener('progress', (e) => {
  const data = JSON.parse(e.data); // { step, percent, message, generationId }
  setProgress(data.percent, data.message);
  if (data.step === 'DONE') es.close();
});
```

#### `GET /api/generations/{id}` → `GenerationDTO`
```json
{ "id": "uuid", "status": "PENDING|PROCESSING|DONE|FAILED", "type": "VIDEO_GENERATION", "videoUrl": "...", "createdAt": "...", "finishedAt": "..." }
```

#### `GET /api/generations/me?limit=10` → `GenerationDTO[]`
#### `GET /api/generations/me/all` → `GenerationDTO[]`

### Projects (Library)

#### `GET /api/v1/projects` → `VideoProjectDTO[]`
```json
{
  "id": "uuid",
  "title": "...",
  "originalPrompt": "...",
  "status": "DRAFT|GENERATING|READY|RENDERING|COMPLETE|FAILED",
  "style": "VideoStyle",
  "currentEdlId": "uuid",
  "currentEdlVersion": 3,
  "generationId": "uuid",
  "createdAt": "...",
  "updatedAt": "..."
}
```

#### `GET /api/v1/projects/{id}` → `VideoProjectDTO`
#### `GET /api/v1/projects/{id}/assets` → `ProjectAssetDTO[]`
#### `GET /api/v1/projects/{id}/assets/{assetId}` → `ProjectAssetDTO`

#### `POST /api/v1/projects/{id}/render?quality=high` → `RenderJobDTO`
Triggers a fresh render of the current EDL.

#### `GET /api/v1/projects/{id}/render/status` → `RenderJobDTO`
```json
{
  "id": "uuid",
  "projectId": "uuid",
  "edlVersionId": "uuid",
  "status": "QUEUED|RENDERING|COMPLETE|FAILED",
  "progress": 0.42,
  "outputUrl": "https://.../final.mp4",
  "quality": "high",
  "startedAt": "...",
  "completedAt": "..."
}
```
Poll every 2-3s while `status` is `QUEUED|RENDERING`.

### Timeline editor

#### `GET /api/v1/projects/{id}/timeline` → raw EDL JSON (string body)
#### `GET /api/v1/projects/{id}/timeline/edl` → typed `EdlDto`
Use this in the editor — has `segments[]`, `audioTracks[]`, `textOverlays[]`, `whisperWords[]`, `subtitleConfig`, `metadata`.

```json
{
  "version": "...",
  "metadata": { "title": "...", "totalDurationMs": 28500, "bpm": 120, "width": 1080, "height": 1920, "fps": 30 },
  "segments": [
    {
      "id": "uuid",
      "asset_id": "uuid",
      "asset_url": "https://.../file",
      "asset_type": "VIDEO|IMAGE",
      "start_ms": 0,
      "end_ms": 5500,
      "trim_in_ms": 0,
      "trim_out_ms": null,
      "layer": 0,
      "effects": [{ "type": "zoom_in", "intensity": 0.8, "params": {} }],
      "transition": { "type": "fade", "durationMs": 300 }
    }
  ],
  "audioTracks": [{ "id": "uuid", "asset_id": "uuid", "asset_url": "...", "type": "voiceover|music", "startMs": 0, "volume": 1.0 }],
  "textOverlays": [{ "text": "...", "startMs": 0, "endMs": 1500, "style": {...}, "position": {...}, "animation": "karaoke|fade_in|..." }],
  "whisperWords": [{ "word": "Hello", "startMs": 100, "endMs": 380 }],
  "subtitleConfig": {...}
}
```

**Layer convention**: `layer: 0` is primary track. Multi-layer scenes (Phase 1) emit `layer >= 1` segments stacked over the same time range — render them as separate tracks in the editor.

**Valid effect types**: `zoom_in zoom_out fast_zoom pan_left pan_right pan_up pan_down shake slow_motion speed_ramp zoom_pulse ken_burns glitch flash bounce drift zoom_in_offset`.

**Valid transitions**: `cut fade fade_white fade_black dissolve wipe_left wipe_right slide_left slide_right`.

#### `PUT /api/v1/projects/{id}/timeline?triggerRender=true` — body `EdlDto`
Validates the modified EDL, saves a new version (`source: USER_MODIFIED`), optionally triggers a re-render. Returns `EdlVersionDTO`.

400 if validation fails — surface error message in the toast.

#### `GET /api/v1/projects/{id}/versions` → `EdlVersionDTO[]`
#### `GET /api/v1/projects/{id}/versions/{version}` → raw EDL JSON

Use for an "undo to previous version" UX or version history sidebar.

### User plans (gating)

#### `GET /api/me/plans/active-plan` — current active plan, gates which features are visible
#### `GET /api/me/plans/{id}/usage` — credit usage

`customMediaAssets` and `customTts` require PRO+ plan. Hide those features for BASIC users.

---

## Frontend state model (suggested)

Don't persist analyze response on the backend — keep in FE state:

```ts
type CreateState = {
  prompt: string
  style: VideoStyle | null
  uploadedAssets: AssetDTO[]               // result of POST /assets/upload
  analysis: PromptAnalysisResponse | null  // result of POST /analyze-prompt
  sceneAssignments: Map<number, UUID>      // sceneIndex → assetId
  generationId: UUID | null                // after POST /assign-assets
  progress: { percent: number; message: string; step: string }
}
```

Default `sceneAssignments` from `analysis.scenes[].suggestedAssetId`. Let user reassign via DnD before submit.

For the timeline editor, hold the full `EdlDto` in state, mutate it locally as the user drags, then PUT on save.

---

## Polling / streaming patterns

- **Generation progress** — SSE on `/progress`. Reconnect on close. Stop when `step === 'DONE'`.
- **Render status** — poll `/render/status` every 2-3s while `QUEUED|RENDERING`. Stop on `COMPLETE|FAILED`.
- **Project list** — refetch on focus or every 10s in the library to surface in-flight renders.

---

## What's already on the backend (don't reimplement)

- Multi-layer scene composition (Phase 1) — UserIntentParser detects "background X, foreground Y" descriptions, LayerAssetGenerator generates background images via FalAI, EdlGenerator emits stacked `layer > 0` segments.
- Scene → asset mapping (Phase 2) — `sceneAssignments` flows through TikTokAdRequest into the pipeline; explicit mappings override default `orderIndex` ordering.
- Timeline editor backend (Phase 3) — `PUT /timeline` validates + saves + re-renders.

## What's coming on the backend (Phase 5 — separate session)

- Music dynamics control (`music_energy` per scene). FE hook will be new fields on `AssetPlacement`. Don't block on it.
- Subtitle style presets (also future) — `EdlSubtitleConfig` will get a `preset` field.

---

## Getting started checklist for the frontend session

1. Pull the new endpoints into the API client layer (typed, ideally generate from OpenAPI if available).
2. Delete the Cloudflare AI image-generation hook + its UI.
3. Build "Create video" screen — prompt + asset uploader + style picker + analyze button.
4. Build preview/scene-assignment UI consuming `PromptAnalysisResponse`.
5. Wire SSE progress + redirect to library on DONE.
6. Build library screen consuming `GET /api/v1/projects`.
7. Build timeline editor consuming `GET /timeline/edl`, save via `PUT /timeline`.
8. Hook plan gating (`GET /api/me/plans/active-plan`) for PRO+ features.
9. Skip styling polish — user handles look & feel.

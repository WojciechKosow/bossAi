# BossAI — Next-session handoff (video generation result)

> Seed prompt for a fresh Claude Code session. The repo `WojciechKosow/bossAi`
> holds BOTH the Spring Boot backend (branch `master`) and the React+Vite
> frontend (branch `frontend`) — they are DIFFERENT branches with different file
> trees. Check out the branch that matches the file you're editing.

## Active branches
- **`claude/video-generation-pipeline-shnk2m`** (frontend, based on `frontend`) — all the Create-flow work. HEAD ≈ `b6002fc`. **Not merged.**
- **`claude/fix-sse-progress-auth`** (backend, based on `master`) — SSE progress `permitAll` + render-completion deferred to Remotion. HEAD ≈ `fbc9dd3`. **Not merged, must be deployed.**
- `claude/allow-uploads-in-beta` (backend) — already **merged** to master (PR #166): upload paywall bypassed in beta mode.

Deploy both unmerged branches (frontend + `claude/fix-sse-progress-auth`) to get all fixes live.

---

## THE CURRENT PROBLEM
On the Create page, after a generation finishes, the inline result plays the
**ffmpeg**-rendered video. It must play the **Remotion**-rendered video (that's
the real product).

### Why (root cause)
The pipeline (`PipelineAsyncRunner.runPipelineAsync`) produces TWO renders:
1. **ffmpeg** — `RenderStep` inside `tikTokAdPipeline.execute()` → sets
   `context.finalVideoUrl`, saved onto `generation.videoUrl`. This is the
   *legacy* video and is done when `generation.status = DONE`.
2. **Remotion** — `videoProductionOrchestrator.produceVideo()` (only when
   `rendering.use-new-pipeline=true`) runs AFTER DONE, creates its OWN
   `RenderJob`, and `markComplete`s it with the Remotion output URL.

The frontend shows the final video from the RenderJob's `outputUrl`
(`GET /api/v1/projects/{id}/render/status` → `RenderJobService.getLatestRenderStatus`
→ latest job by `startedAt`; `@PrePersist` sets `startedAt` at creation, so the
Remotion job — created later — is the one returned). **That part is correct.**

ffmpeg leaks into the result via TWO paths — fix both:

- **A (backend, deploy):** without `claude/fix-sse-progress-auth`,
  `AssetBridgeService.bootstrapEdlAndRender` marks its RenderJob COMPLETE with
  the ffmpeg mp4 immediately. The frontend sees that COMPLETE (ffmpeg) job
  *before* the Remotion job exists → shows ffmpeg. The branch fixes this by
  leaving the bootstrap job QUEUED when `use-new-pipeline=true`.
- **B (frontend, code):** `CreateVideoPage` completion effect has a 15s fallback
  that calls `getGeneration(id)` and uses `gen.videoUrl` — which IS the ffmpeg
  mp4. If the project/render is slow to surface, this fires and shows ffmpeg.

---

## TO-DO (in order)

### 1. Frontend — remove the ffmpeg fallback  (`claude/video-generation-pipeline-shnk2m`)
File: `src/pages/dashboard/CreateVideoPage.tsx`, the "surface the finished video
inline" effect (search `settledResultRef`).
- DELETE the `window.setTimeout(... getGeneration(generationId) ... gen.videoUrl ...)`
  fallback block entirely. The final video must ONLY be `render.outputUrl` from a
  COMPLETE render job.
- Keep: `if (render?.status === "COMPLETE" && render.outputUrl) finish(absoluteUrl(...))`.
- Add: `if (render?.status === "FAILED")` → show a failure state (reuse `FailedPanel`
  with a message; add a `renderFailed` state or similar).
- Otherwise (QUEUED / RENDERING / undefined) → keep waiting; `FinalizingPanel`
  ("Rendering the final cut") already covers this. Remotion can take minutes — do
  NOT time out into ffmpeg.
- Optional but recommended: on `done`, `useQueryClient().invalidateQueries({queryKey:["projects"]})`
  so the bridged project (hence render polling) is found immediately instead of
  waiting up to 10s for the `useProjects` refetch.
- Remove the now-unused `getGeneration` import.
- `absoluteUrl`, `useRenderStatus`, `RenderJobDTO`, `FinalizingPanel`, `ResultPanel`
  already exist and are wired. `render` comes from
  `useRenderStatus(projectId, { enabled: step==="generating" && done && !!projectId })`.

### 2. Backend — confirm the deferral is deployed  (`claude/fix-sse-progress-auth`)
- `bootstrapEdlAndRender(..., markComplete = !useNewPipeline)` — when the new
  pipeline is on, the bootstrap RenderJob stays QUEUED so `getLatestRenderStatus`
  returns the Remotion job. (Already implemented; just needs deploy.)
- Confirm `rendering.use-new-pipeline=true` is actually set in the backend
  environment (it's not in the repo; defaults to `false`).

### 3. Verify end-to-end (needs the REAL backend + Remotion + `use-new-pipeline=true`)
- Generate a video from the Create page.
- `GET /api/v1/projects/{id}/render/status` should go QUEUED → RENDERING →
  COMPLETE, and the `outputUrl` at COMPLETE must be the **Remotion** output.
- The Create page result must play that URL (not `generation.videoUrl`).
- Prior work was only verified against MOCKED endpoints via Playwright
  (`/opt/pw-browsers/chromium-1194`); no real backend run was possible in-session.

---

## Lower-priority open items (raised, not done)
- **CORS**: `SecurityConfig.corsConfigurationSource()` allows only
  `http://localhost:5173` and `http://localhost:1420/` (note: trailing slash never
  matches). The deployed frontend origin must be added or all browser calls
  CORS-fail. Suggest making it an env var (`APP_CORS_ALLOWED_ORIGINS`).
- **Ephemeral storage on Railway**: uploaded assets + generated media write to
  local disk (`StorageService`, `ImageStorageService` hardcoded `uploads/images`).
  Railway disks are ephemeral → files vanish on redeploy. Needs object storage
  (S3/R2) or a persistent volume.
- **`OperationCost`**: `@Table(name="operation_costs")` is commented out → entity
  maps to default `operation_cost`; verify that matches the real DB table.
- **Reload-resume**: if the user reloads the Create page mid-render, the
  generating/result view is not restored (they see the compose form; the
  `GenerationNotifier` still pings on completion). Consider resuming from the
  `activeGeneration` localStorage id.

## Key files
- Frontend completion: `src/pages/dashboard/CreateVideoPage.tsx`
- Frontend hooks: `src/features/video/hooks.ts` (`useGenerationProgress` polling,
  `useRenderStatus`), `src/features/video/api.ts` (`getRenderStatus`, `absoluteUrl`).
- Cross-page notify: `src/features/video/components/GenerationNotifier.tsx`,
  `src/features/video/activeGeneration.ts`.
- Backend render lifecycle: `service/PipelineAsyncRunner.java`,
  `service/edl/AssetBridgeService.java` (`bootstrapEdlAndRender`),
  `service/RenderJobService.java` (`getLatestRenderStatus`, `createRenderJob`,
  `markComplete`), `service/edl/VideoProductionOrchestrator.java` (`produceVideo`).

## Build/verify commands
- Frontend: `npm ci && npx tsc -b && npm run build`. Drive with Playwright via
  `vite preview` + mocked `/api/**` routes (chromium at
  `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`).
- Backend: `./mvnw -q -o compile -DskipTests` (deps cached in `~/.m2`).

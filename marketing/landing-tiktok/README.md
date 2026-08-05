# Landing-page TikTok animation

A vertical (1080×1920, 9:16) promo video built entirely from the landing
page's own brand — the Toucan mark, the blue→cyan gradient, Inter, the phone
mockup, the four-step workflow and the real landing copy. Nothing is faked or
imported from outside the repo.

## Output

- **`toucan-tiktok.mp4`** — the deliverable. 1080×1920, 30 fps, ~21 s, H.264
  (yuv420p, `+faststart`) — ready to upload to TikTok / Reels / Shorts.

## Scenes

1. **Intro** — Toucan Motion logo + "Turn ideas into viral TikToks in seconds."
2. **Workflow** — the four moves: Upload → Describe → Toucan edits → Export.
3. **Watch it work** — the phone mockup generating a video (0 → 73%), with the
   floating "Auto-cut engine" / "Beat-synced" badges.
4. **What you get** — AI script, Auto-cut engine, Ready to post.
5. **CTA** — "Ready to start creating?" on the brand gradient.

## Brand icon

The toucan mark is the repo's own **`public/favicon.png`**. `genmask.cjs` turns
it into an alpha silhouette (bird = opaque, white background = transparent,
internal white lines kept as gaps), auto-crops the padding, and inlines it into
`render.html` as the `MASK` data URI. The animation then fills that silhouette
with the brand gradient (dark scenes) or solid white (the CTA), so the single
icon recolors itself for every background. A data URI is required — Chromium
will not load a `file://` image as a CSS mask.

If the favicon ever changes, re-run `node genmask.cjs` and then `node capture.cjs`.

## Source

- **`render.html`** — a single, self-contained page that draws the whole
  animation on a deterministic, seekable clock. `window.seek(t)` renders the
  exact state at time `t` (seconds), so every frame is reproducible.
- **`genmask.cjs`** — regenerates the icon mask from `public/favicon.png` and
  patches it into `render.html`.
- **`fonts-embed.css`** — Inter (weights 400–900) embedded as data URIs so the
  render needs no network and the type is pixel-identical every run.
- **`capture.cjs`** — drives `render.html` with Playwright/Chromium, seeks frame
  by frame, and pipes JPEG frames straight into ffmpeg (no temp files).

## Regenerate

```bash
cd marketing/landing-tiktok
npm install playwright ffmpeg-static
node capture.cjs          # writes toucan-tiktok.mp4
```

Set `CHROMIUM_PATH` to use a specific Chromium build; otherwise Playwright's
bundled one is used. Tunables live at the top of `capture.cjs` (`FPS`, `TOTAL`,
`HOLD`) and in the `scenes` / `captions` arrays inside `render.html`.

To preview a single frame, open `render.html` in a browser and run
`seek(9.0)` in the console.

import express from "express";
import path from "path";
import fs from "fs";
import { v4 as uuidv4 } from "uuid";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";
import { RenderRequestSchema, RenderJob } from "./types/edl";

const app = express();
app.use(express.json({ limit: "50mb" }));

// In-memory render job store
const renderJobs = new Map<string, RenderJob>();

const OUTPUT_DIR = path.resolve(process.cwd(), "output");
if (!fs.existsSync(OUTPUT_DIR)) {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
}

// POST /api/v1/render — queue a new render
app.post("/api/v1/render", async (req, res) => {
  const parseResult = RenderRequestSchema.safeParse(req.body);

  if (!parseResult.success) {
    res.status(400).json({
      error: "Invalid request body",
      details: parseResult.error.flatten(),
    });
    return;
  }

  const renderRequest = parseResult.data;
  const renderId = renderRequest.render_id ?? `render-${uuidv4().slice(0, 8)}`;

  const job: RenderJob = {
    render_id: renderId,
    status: "queued",
    progress: 0,
    output_url: null,
    created_at: new Date(),
  };

  renderJobs.set(renderId, job);
  res.status(202).json({ render_id: renderId, status: "queued" });

  // Start render asynchronously
  startRender(renderId, renderRequest).catch((err) => {
    console.error(`Render ${renderId} failed:`, err);
    const failedJob = renderJobs.get(renderId);
    if (failedJob) {
      failedJob.status = "failed";
      failedJob.error = err instanceof Error ? err.message : String(err);
    }
  });
});

// GET /api/v1/render/:render_id/status — check render status
app.get("/api/v1/render/:render_id/status", (req, res) => {
  const { render_id } = req.params;
  const job = renderJobs.get(render_id);

  if (!job) {
    res.status(404).json({ error: "Render not found" });
    return;
  }

  res.json({
    render_id: job.render_id,
    status: job.status,
    progress: job.progress,
    output_url: job.output_url,
    error: job.error ?? null,
  });
});

// GET /api/v1/renders — list all render jobs
app.get("/api/v1/renders", (_req, res) => {
  const jobs = Array.from(renderJobs.values()).map((job) => ({
    render_id: job.render_id,
    status: job.status,
    progress: job.progress,
    output_url: job.output_url,
    created_at: job.created_at,
  }));
  res.json({ renders: jobs });
});

// Serve rendered files
app.use("/output", express.static(OUTPUT_DIR));

// Health check
app.get("/health", (_req, res) => {
  res.json({ status: "ok" });
});

async function startRender(
  renderId: string,
  request: ReturnType<typeof RenderRequestSchema.parse>
) {
  const job = renderJobs.get(renderId)!;
  job.status = "rendering";

  const entryPoint = path.resolve(__dirname, "index.tsx");

  // Bundle the Remotion project
  console.log(`[${renderId}] Bundling...`);
  const bundled = await bundle({
    entryPoint,
    onProgress: (progress) => {
      job.progress = progress * 0.2; // Bundling is 0-20%
    },
  });

  const { edl, output_config: outputConfig } = request;
  const { metadata } = edl;

  const fps = outputConfig?.fps ?? metadata.fps;
  const width = outputConfig?.width ?? metadata.width;
  const height = outputConfig?.height ?? metadata.height;
  const durationInFrames = Math.round((metadata.total_duration_ms / 1000) * fps);

  console.log(
    `[${renderId}] Rendering ${durationInFrames} frames at ${width}x${height}...`
  );

  const composition = await selectComposition({
    serveUrl: bundled,
    id: "TikTokVideo",
    inputProps: { edl },
  });

  const outputPath = path.join(OUTPUT_DIR, `${renderId}.mp4`);

  await renderMedia({
    composition: {
      ...composition,
      width,
      height,
      fps,
      durationInFrames,
      props: { edl },
    },
    serveUrl: bundled,
    codec: "h264",
    outputLocation: outputPath,
    onProgress: ({ progress }) => {
      job.progress = 0.2 + progress * 0.8; // Rendering is 20-100%
    },
  });

  job.status = "completed";
  job.progress = 1;
  job.output_url = `/output/${renderId}.mp4`;

  console.log(`[${renderId}] Complete: ${job.output_url}`);
}

const PORT = parseInt(process.env.PORT ?? "3000", 10);

// Only start listening when run directly (not when imported in tests)
if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`Remotion Renderer server listening on port ${PORT}`);
  });
}

export { app };

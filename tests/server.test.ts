import request from "supertest";
import { app } from "../src/server";

// Mock Remotion bundler and renderer
jest.mock("@remotion/bundler", () => ({
  bundle: jest.fn().mockResolvedValue("/tmp/mock-bundle"),
}));

jest.mock("@remotion/renderer", () => ({
  renderMedia: jest.fn().mockResolvedValue(undefined),
  selectComposition: jest.fn().mockResolvedValue({
    id: "TikTokVideo",
    width: 1080,
    height: 1920,
    fps: 30,
    durationInFrames: 450,
    props: {},
  }),
}));

const validBody = {
  render_id: "test-render-001",
  edl: {
    version: "1.0",
    metadata: {
      title: "Test",
      total_duration_ms: 15000,
      width: 1080,
      height: 1920,
      fps: 30,
    },
    segments: [
      {
        id: "seg-1",
        asset_url: "storage/projects/abc/scene_0.mp4",
        asset_type: "VIDEO",
        start_ms: 0,
        end_ms: 5000,
      },
    ],
    audio_tracks: [
      {
        id: "voice-1",
        asset_url: "storage/projects/abc/voice.mp3",
        type: "voiceover",
      },
    ],
  },
  output_config: {
    width: 1080,
    height: 1920,
    fps: 30,
    codec: "h264",
    quality: "high",
  },
};

describe("Server API", () => {
  describe("GET /health", () => {
    it("returns ok status", async () => {
      const res = await request(app).get("/health");
      expect(res.status).toBe(200);
      expect(res.body.status).toBe("ok");
    });
  });

  describe("POST /api/v1/render", () => {
    it("returns 202 with render_id for valid request", async () => {
      const res = await request(app)
        .post("/api/v1/render")
        .send(validBody);

      expect(res.status).toBe(202);
      expect(res.body.render_id).toBe("test-render-001");
      expect(res.body.status).toBe("queued");
    });

    it("generates render_id if not provided", async () => {
      const body = {
        edl: {
          metadata: { total_duration_ms: 5000 },
          segments: [
            {
              id: "s1",
              asset_url: "storage/v.mp4",
              asset_type: "VIDEO",
              start_ms: 0,
              end_ms: 5000,
            },
          ],
        },
      };
      const res = await request(app).post("/api/v1/render").send(body);
      expect(res.status).toBe(202);
      expect(res.body.render_id).toBeDefined();
    });

    it("returns 400 for invalid request body", async () => {
      const res = await request(app)
        .post("/api/v1/render")
        .send({ edl: {} });

      expect(res.status).toBe(400);
      expect(res.body.error).toBe("Invalid request body");
    });

    it("returns 400 for missing edl", async () => {
      const res = await request(app)
        .post("/api/v1/render")
        .send({});

      expect(res.status).toBe(400);
    });
  });

  describe("GET /api/v1/render/:render_id/status", () => {
    it("returns 404 for nonexistent render", async () => {
      const res = await request(app).get(
        "/api/v1/render/render-nonexistent/status"
      );
      expect(res.status).toBe(404);
      expect(res.body.error).toBe("Render not found");
    });

    it("returns status for a submitted render", async () => {
      const createRes = await request(app)
        .post("/api/v1/render")
        .send(validBody);

      const renderId = createRes.body.render_id;

      const statusRes = await request(app).get(
        `/api/v1/render/${renderId}/status`
      );
      expect(statusRes.status).toBe(200);
      expect(statusRes.body.render_id).toBe(renderId);
      expect(["queued", "rendering", "completed"]).toContain(
        statusRes.body.status
      );
      expect(statusRes.body).toHaveProperty("progress");
      expect(statusRes.body).toHaveProperty("output_url");
      expect(statusRes.body).toHaveProperty("error");
    });
  });

  describe("GET /api/v1/renders", () => {
    it("returns list of renders", async () => {
      const res = await request(app).get("/api/v1/renders");
      expect(res.status).toBe(200);
      expect(res.body.renders).toBeDefined();
      expect(Array.isArray(res.body.renders)).toBe(true);
    });
  });
});

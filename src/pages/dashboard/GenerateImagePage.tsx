import { useState, useEffect } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "@/lib/axios";
import { Loader2 } from "lucide-react";

const API_URL = import.meta.env.VITE_API_URL || "http://localhost:8080";

const GenerateImagePage = () => {
  const queryClient = useQueryClient();

  const [prompt, setPrompt] = useState("");
  const [generationId, setGenerationId] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);

  /* -------------------- GENERATE -------------------- */

  const generateMutation = useMutation({
    mutationFn: async () => {
      const res = await axios.post("/api/generations/image", {
        prompt,
        imageUrl: null,
      });
      return res.data;
    },
    onSuccess: (data) => {
      setGenerationId(data.id);
      setStatus(data.status);
      setImageUrl(null);
    },
  });

  /* -------------------- POLLING -------------------- */

  useEffect(() => {
    if (!generationId) return;

    const interval = setInterval(async () => {
      try {
        const res = await axios.get(`/api/generations/${generationId}`);

        const generation = res.data;

        setStatus(generation.generationStatus);

        if (generation.generationStatus === "DONE") {
          setImageUrl(`${API_URL}${generation.imageUrl}`);

          clearInterval(interval);

          // refresh dashboard + gallery
          queryClient.invalidateQueries({
            queryKey: ["recent-generations"],
          });
          queryClient.invalidateQueries({
            queryKey: ["active-plan"],
          });
        }

        if (generation.generationStatus === "FAILED") {
          clearInterval(interval);
        }
      } catch (err) {
        clearInterval(interval);
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [generationId]);

  /* -------------------- SUBMIT -------------------- */

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!prompt.trim()) return;

    generateMutation.mutate();
  };

  return (
    <div className="max-w-4xl mx-auto space-y-10">
      {/* HEADER */}
      <div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          Generate Image
        </h1>
        <p className="text-muted-foreground mt-2">
          Describe what you want to create.
        </p>
      </div>

      {/* FORM */}
      <form
        onSubmit={handleSubmit}
        className="bg-card border border-border rounded-xl p-6 space-y-4"
      >
        <textarea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder="A futuristic city floating in the sky..."
          className="w-full min-h-[120px] rounded-lg border border-border bg-background p-4 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-primary"
        />

        <button
          type="submit"
          disabled={generateMutation.isPending}
          className="w-full bg-primary text-primary-foreground py-2.5 rounded-lg text-sm font-medium hover:opacity-90 transition flex items-center justify-center gap-2"
        >
          {generateMutation.isPending && (
            <Loader2 className="h-4 w-4 animate-spin" />
          )}
          {generateMutation.isPending ? "Starting..." : "Generate Image"}
        </button>
      </form>

      {/* STATUS */}
      {status && (
        <div className="bg-card border border-border rounded-xl p-6 text-center">
          {status === "PENDING" && (
            <StatusBlock text="Preparing generation..." />
          )}

          {status === "PROCESSING" && (
            <StatusBlock text="Generating your image..." />
          )}

          {status === "FAILED" && (
            <p className="text-red-500 text-sm">
              Generation failed. Try again.
            </p>
          )}

          {status === "DONE" && imageUrl && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                Generation complete
              </p>

              <img
                src={imageUrl}
                alt="Generated"
                className="rounded-xl mx-auto max-h-[400px] object-contain border border-border"
              />

              <a
                href={imageUrl}
                download
                className="inline-block text-sm text-primary hover:underline"
              >
                Download image
              </a>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default GenerateImagePage;

/* -------------------- COMPONENT -------------------- */

const StatusBlock = ({ text }: { text: string }) => (
  <div className="flex flex-col items-center gap-3 text-sm text-muted-foreground">
    <Loader2 className="h-5 w-5 animate-spin" />
    {text}
  </div>
);

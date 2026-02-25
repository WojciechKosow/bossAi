import { useState } from "react";
import { Download, Trash2, Image as ImageIcon, Loader2 } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "@/lib/axios";

const API_URL = import.meta.env.VITE_API_URL || "http://localhost:8080";

const GalleryPage = () => {
  const queryClient = useQueryClient();
  const [sortNewest, setSortNewest] = useState(true);

  /* ---------------- FETCH ---------------- */

  const { data: generations, isLoading } = useQuery({
    queryKey: ["gallery-generations"],
    queryFn: async () => {
      const res = await axios.get("/api/generations/me/all");
      return res.data;
    },
  });

  /* ---------------- DELETE ---------------- */

  const deleteMutation = useMutation({
    mutationFn: async (id: string) => {
      await axios.delete(`/api/generations/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["gallery-generations"],
      });
    },
  });

  /* ---------------- SORT ---------------- */

  const sorted =
    generations
      ?.slice()
      .sort((a: any, b: any) =>
        sortNewest
          ? new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
          : new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
      ) ?? [];

  /* ---------------- UI ---------------- */

  return (
    <div className="max-w-6xl mx-auto space-y-10">
      {/* HEADER */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            Gallery
          </h1>
          <p className="text-muted-foreground mt-2">
            All your generated content in one place.
          </p>
        </div>

        <button
          onClick={() => setSortNewest((prev) => !prev)}
          className="border border-border px-4 py-2 rounded-lg text-sm hover:bg-muted transition"
        >
          Sort: {sortNewest ? "Newest" : "Oldest"}
        </button>
      </div>

      {/* LOADING */}
      {isLoading && (
        <div className="flex justify-center py-20">
          <Loader2 className="animate-spin" />
        </div>
      )}

      {/* EMPTY */}
      {!isLoading && sorted.length === 0 && (
        <div className="border border-dashed border-border rounded-xl p-12 text-center text-muted-foreground">
          <ImageIcon className="mx-auto mb-4" size={32} />
          No generations yet.
        </div>
      )}

      {/* GRID */}
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {sorted.map((gen: any) => (
          <div
            key={gen.id}
            className="relative group rounded-xl overflow-hidden border border-border bg-card hover:shadow-md transition"
          >
            {gen.imageUrl && (
              <img
                src={`${API_URL}${gen.imageUrl}`}
                alt="Generated"
                className="w-full h-56 object-cover"
              />
            )}

            {gen.generationStatus !== "DONE" && (
              <div className="absolute inset-0 bg-black/60 flex items-center justify-center text-white text-sm">
                {gen.generationStatus}
              </div>
            )}

            {/* Hover Overlay */}
            {gen.generationStatus === "DONE" && (
              <div className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition flex items-center justify-center gap-4">
                <a
                  href={`${API_URL}${gen.imageUrl}`}
                  download
                  className="bg-white text-black p-2 rounded-md hover:scale-105 transition"
                >
                  <Download size={16} />
                </a>

                <button
                  onClick={() => deleteMutation.mutate(gen.id)}
                  className="bg-white text-black p-2 rounded-md hover:scale-105 transition"
                >
                  <Trash2 size={16} />
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

export default GalleryPage;

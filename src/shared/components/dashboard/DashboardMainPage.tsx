import { useAuth } from "../../../features/auth/context/AuthContext";
import { motion } from "framer-motion";
import { useEffect, useState } from "react";

const mockContent = [
  {
    id: 1,
    type: "image",
    url: "https://images.unsplash.com/photo-1682687220742-aba13b6e50ba",
  },
  {
    id: 2,
    type: "image",
    url: "https://images.unsplash.com/photo-1682687220923-c58b9a4592ae",
  },
  {
    id: 3,
    type: "video",
    url: "https://www.w3schools.com/html/mov_bbb.mp4",
  },
  {
    id: 4,
    type: "image",
    url: "https://images.unsplash.com/photo-1682687220199-d0124f48f95b",
  },
];

const DashboardMainPage = () => {
  const { user, isLoading } = useAuth();
  const [currentSlide, setCurrentSlide] = useState(0);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <p className="text-sm text-muted-foreground animate-pulse">
          Loading...
        </p>
      </div>
    );
  }

  const useCounter = (value: number, duration = 1000) => {
    const [count, setCount] = useState(0);

    useEffect(() => {
      let start = 0;
      const increment = value / (duration / 16);

      const timer = setInterval(() => {
        start += increment;
        if (start >= value) {
          setCount(value);
          clearInterval(timer);
        } else {
          setCount(Math.floor(start));
        }
      }, 16);

      return () => clearInterval(timer);
    }, [value, duration]);

    return count;
  };

  useEffect(() => {
    const interval = setInterval(() => {
      setCurrentSlide((prev) =>
        prev === mockContent.length - 1 ? 0 : prev + 1,
      );
    }, 4000);

    return () => clearInterval(interval);
  }, []);

  const imagesCount = mockContent.filter((i) => i.type === "image").length;
  const videosCount = mockContent.filter((i) => i.type === "video").length;
  const animatedImages = useCounter(imagesCount);
  const animatedVideos = useCounter(videosCount);
  const animatedTotal = useCounter(imagesCount + videosCount);

  const nextSlide = () =>
    setCurrentSlide((prev) => (prev === mockContent.length - 1 ? 0 : prev + 1));

  const prevSlide = () =>
    setCurrentSlide((prev) => (prev === 0 ? mockContent.length - 1 : prev - 1));

  return (
    <div className="max-w-7xl mx-auto px-6 py-12 space-y-16">
      {/* HERO */}
      <section className="relative overflow-hidden rounded-2xl border bg-white p-12 shadow-sm">
        {/* Floating gradient blob */}
        <div className="absolute -top-20 -right-20 w-72 h-72 bg-purple-200 rounded-full blur-3xl opacity-40 animate-pulse" />
        <div className="absolute -bottom-20 -left-20 w-72 h-72 bg-blue-200 rounded-full blur-3xl opacity-40 animate-pulse" />

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          className="relative max-w-3xl space-y-6"
        >
          <h1 className="text-4xl font-semibold leading-tight">
            Create high-converting AI visuals in seconds
          </h1>

          <p className="text-lg text-muted-foreground">
            You generated <span className="font-medium text-black">12 ads</span>{" "}
            this week 🚀
          </p>

          <div className="flex gap-4">
            <motion.button
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.97 }}
              className="px-6 py-3 rounded-lg bg-black text-white font-medium shadow-lg"
            >
              Create Image
            </motion.button>

            <motion.button
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.97 }}
              className="px-6 py-3 rounded-lg border font-medium hover:bg-gray-100"
            >
              Create Video
            </motion.button>
          </div>
        </motion.div>
      </section>
      {/* USAGE STATS */}
      <section className="grid grid-cols-1 sm:grid-cols-3 gap-6">
        {[
          { label: "Images generated", value: animatedImages },
          { label: "Videos generated", value: animatedVideos },
          { label: "Total renders", value: animatedTotal },
        ].map((stat, i) => (
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 15 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.1 }}
            whileHover={{ y: -4 }}
            className="p-6 rounded-xl border bg-white shadow-sm hover:shadow-md transition"
          >
            <p className="text-sm text-muted-foreground">{stat.label}</p>
            <p className="text-3xl font-semibold mt-2">{stat.value}</p>
            <p className="text-xs text-green-600 mt-2">+12% this week</p>
          </motion.div>
        ))}
      </section>

      {/* RECENT PROJECTS CAROUSEL */}
      <section className="space-y-6">
        <h2 className="text-2xl font-semibold">Recent projects</h2>

        <div className="relative rounded-2xl overflow-hidden border bg-white shadow-sm">
          {mockContent[currentSlide].type === "image" ? (
            <img
              src={`${mockContent[currentSlide].url}?auto=format&fit=crop&w=1200&q=80`}
              className="w-full h-[400px] object-cover"
              alt="Recent project"
            />
          ) : (
            <video
              src={mockContent[currentSlide].url}
              className="w-full h-[400px] object-cover"
              controls
            />
          )}

          {/* Controls */}
          <button
            onClick={prevSlide}
            className="absolute left-4 top-1/2 -translate-y-1/2 bg-white/80 backdrop-blur px-3 py-2 rounded-lg shadow"
          >
            ←
          </button>
          <button
            onClick={nextSlide}
            className="absolute right-4 top-1/2 -translate-y-1/2 bg-white/80 backdrop-blur px-3 py-2 rounded-lg shadow"
          >
            →
          </button>
        </div>
      </section>

      {/* GALLERY */}
      <section className="space-y-6">
        <div className="flex items-center justify-between">
          <h2 className="text-2xl font-semibold">All creations</h2>
          <button className="text-sm text-muted-foreground hover:text-black transition">
            View all
          </button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
          {mockContent.map((item) => (
            <div
              key={item.id}
              className="group rounded-xl overflow-hidden border bg-white hover:shadow-md transition"
            >
              {item.type === "image" ? (
                <img
                  src={`${item.url}?auto=format&fit=crop&w=800&q=80`}
                  alt="Generated content"
                  className="w-full h-64 object-cover"
                />
              ) : (
                <video
                  src={item.url}
                  className="w-full h-64 object-cover"
                  controls
                />
              )}

              <div className="px-4 py-3 text-sm text-muted-foreground border-t bg-gray-50">
                {item.type === "image" ? "AI Image" : "Video Ad"}
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
};

export default DashboardMainPage;

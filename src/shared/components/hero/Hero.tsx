const Hero = () => {
  return (
    <div className="pt-32 pb-28 bg-background">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <h1 className="text-5xl md:text-6xl font-semibold leading-tight text-gray-900">
          Learn without limits.
        </h1>

        <p className="mt-6 text-lg md:text-xl text-gray-600 max-w-3xl mx-auto">
          Access high-quality courses taught by real experts. Grow your skills,
          build your career, and unlock new opportunities.
        </p>

        <div className="mt-10 flex justify-center gap-4">
          <a
            href="/register"
            className="px-6 py-3 rounded-lg bg-black text-white hover:bg-gray-900 transition font-medium"
          >
            Get Started
          </a>
          <a
            href="/login"
            className="px-6 py-3 rounded-lg border border-gray-300 text-gray-800 hover:border-gray-800 transition font-medium"
          >
            Browse Courses
          </a>
        </div>
      </div>
    </div>
  );
};

export default Hero;

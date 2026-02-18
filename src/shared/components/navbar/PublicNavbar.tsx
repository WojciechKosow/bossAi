import { Link } from "react-router";

const PublicNavbar = () => {
  return (
    <nav className="w-full border-b border-gray-200 bg-white/80 backdrop-blur-md fixed top-0 left-0 z-50">
      <div className="max-w-7xl mx-auto px-8 py-4 flex justify-between items-center">
        <Link to="/" className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-black flex items-center justify-center text-white font-bold text-lg">
            T
          </div>
          <span className="text-xl font-semibold tracking-tight text-gray-900">
            Toucan
          </span>
        </Link>

        <div className="hidden md:flex items-center gap-10 text-sm text-gray-700">
          <a href="#features" className="hover:text-gray-900 transition">
            Features
          </a>
          <a href="#testimonials" className="hover:text-gray-900 transition">
            Testimonials
          </a>
          <a href="#pricing" className="hover:text-gray-900 transition">
            Pricing
          </a>
        </div>

        <div className="flex items-center gap-3">
          <Link
            to="/login"
            className="px-4 py-2 rounded-lg border border-gray-400 text-gray-800 hover:border-gray-900 transition"
          >
            Sign In
          </Link>

          <Link
            to="/register"
            className="px-4 py-2 rounded-lg bg-black text-white hover:bg-gray-900 transition"
          >
            Get Started
          </Link>
        </div>
      </div>
    </nav>
  );
};

export default PublicNavbar;

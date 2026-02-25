import { Link } from "react-router-dom";
import ThemeToggle from "../ThemeToggle";

const PublicNavbar = () => {
  return (
    <nav className="w-full border-b border-border bg-background/80 backdrop-blur-md fixed top-0 left-0 z-50">
      <div className="max-w-7xl mx-auto px-8 py-4 flex justify-between items-center">
        {/* LOGO */}
        <Link to="/" className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-primary text-primary-foreground flex items-center justify-center font-semibold text-lg">
            T
          </div>
          <span className="text-xl font-semibold tracking-tight text-foreground">
            Toucan
          </span>
        </Link>

        {/* LINKS */}
        <div className="hidden md:flex items-center gap-10 text-sm text-muted-foreground">
          <a href="#features" className="hover:text-foreground transition">
            Features
          </a>
          <a href="#testimonials" className="hover:text-foreground transition">
            Testimonials
          </a>
          <a href="#pricing" className="hover:text-foreground transition">
            Pricing
          </a>
        </div>

        {/* ACTIONS */}
        <div className="flex items-center gap-3">
          <Link
            to="/login"
            className="px-4 py-2 rounded-lg border border-border bg-background text-foreground hover:bg-muted transition"
          >
            Sign In
          </Link>

          <Link
            to="/register"
            className="px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:opacity-90 transition"
          >
            Get Started
          </Link>
        </div>
      </div>
    </nav>
  );
};

export default PublicNavbar;

import { Link } from "react-router-dom";
import { BETA_MODE } from "@/lib/betaMode";

const PublicNavbar = () => {
  const scrollToWaitlist = () => {
    document.getElementById("waitlist")?.scrollIntoView({ behavior: "smooth" });
  };

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

        {/* NAV LINKS */}
        <div className="hidden md:flex items-center gap-10 text-sm text-muted-foreground">
          <a href="#features" className="hover:text-foreground transition">Features</a>
          {!BETA_MODE && (
            <a href="#pricing" className="hover:text-foreground transition">Pricing</a>
          )}
          <a href="#waitlist" className="hover:text-foreground transition">
            {BETA_MODE ? "Join beta" : "Testimonials"}
          </a>
        </div>

        {/* ACTIONS */}
        <div className="flex items-center gap-3">
          {BETA_MODE ? (
            <button
              onClick={scrollToWaitlist}
              className="px-4 py-2 rounded-lg gradient-bg text-primary-foreground text-sm font-medium hover:opacity-90 transition"
            >
              Request access
            </button>
          ) : (
            <>
              <Link
                to="/login"
                className="px-4 py-2 rounded-lg border border-border bg-background text-foreground hover:bg-muted transition text-sm"
              >
                Sign In
              </Link>
              <Link
                to="/register"
                className="px-4 py-2 rounded-lg bg-primary text-primary-foreground hover:opacity-90 transition text-sm"
              >
                Get Started
              </Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
};

export default PublicNavbar;

import { Link } from "react-router-dom";
import { ArrowRight } from "lucide-react";
import { BETA_MODE } from "@/lib/betaMode";

const PublicNavbar = () => {
  const scrollToWaitlist = () => {
    document.getElementById("waitlist")?.scrollIntoView({ behavior: "smooth" });
  };

  return (
    <nav className="w-full fixed top-0 left-0 z-50 border-b border-border/60 bg-background/80 backdrop-blur-xl">
      <div className="max-w-7xl mx-auto px-6 md:px-10 py-3.5 flex items-center justify-between gap-6">

        {/* Logo */}
        <Link to="/" className="flex items-center gap-2.5 flex-shrink-0">
          <div className="w-8 h-8 rounded-lg gradient-bg flex items-center justify-center shadow-glow flex-shrink-0">
            <span className="text-white font-black text-[15px] leading-none">T</span>
          </div>
          <span className="text-[17px] font-bold tracking-tight text-foreground">
            Toucan
          </span>
        </Link>

        {/* Nav links */}
        <div className="hidden md:flex items-center gap-8 text-[13px] text-muted-foreground">
          <a
            href="#features"
            className="hover:text-foreground transition-colors duration-150 font-medium"
          >
            Features
          </a>
          {!BETA_MODE && (
            <a
              href="#pricing"
              className="hover:text-foreground transition-colors duration-150 font-medium"
            >
              Pricing
            </a>
          )}
          <a
            href={BETA_MODE ? "#waitlist" : "#testimonials"}
            className="hover:text-foreground transition-colors duration-150 font-medium"
          >
            {BETA_MODE ? "Join beta" : "Testimonials"}
          </a>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2 flex-shrink-0">
          {BETA_MODE ? (
            <button
              onClick={scrollToWaitlist}
              className="group inline-flex items-center gap-1.5 px-4 py-2 rounded-lg gradient-bg text-white text-[13px] font-semibold hover:opacity-90 active:scale-[0.97] transition-all duration-200 shadow-glow"
            >
              Request access
              <ArrowRight
                size={13}
                className="group-hover:translate-x-0.5 transition-transform duration-200"
              />
            </button>
          ) : (
            <>
              <Link
                to="/login"
                className="px-4 py-2 rounded-lg text-foreground/80 hover:text-foreground hover:bg-muted/60 transition-all duration-150 text-[13px] font-medium"
              >
                Sign in
              </Link>
              <Link
                to="/register"
                className="group inline-flex items-center gap-1.5 px-4 py-2 rounded-lg gradient-bg text-white text-[13px] font-semibold hover:opacity-90 active:scale-[0.97] transition-all duration-200 shadow-glow"
              >
                Get started
                <ArrowRight
                  size={13}
                  className="group-hover:translate-x-0.5 transition-transform duration-200"
                />
              </Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
};

export default PublicNavbar;

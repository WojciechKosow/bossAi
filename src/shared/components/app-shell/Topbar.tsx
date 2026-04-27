import { Menu, Sparkles } from "lucide-react";
import { Link } from "react-router-dom";
import ThemeToggle from "../ThemeToggle";
import { Button } from "@/components/ui/button";

type Props = {
  onMenuClick?: () => void;
};

export const Topbar = ({ onMenuClick }: Props) => {
  return (
    <header className="h-14 border-b border-border bg-card/70 backdrop-blur px-4 sm:px-6 lg:px-8 flex items-center justify-between sticky top-0 z-30">
      <div className="flex items-center gap-3">
        <button
          onClick={onMenuClick}
          className="lg:hidden text-muted-foreground hover:text-foreground"
        >
          <Menu size={18} />
        </button>
      </div>
      <div className="flex items-center gap-2">
        <ThemeToggle />
        <Link to="/dashboard/create">
          <Button size="sm" className="gradient-bg text-white shadow-glow">
            <Sparkles size={14} /> New video
          </Button>
        </Link>
      </div>
    </header>
  );
};

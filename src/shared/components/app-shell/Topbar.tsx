import { Search, Menu } from "lucide-react";
import { useAuth } from "../../../features/auth/context/AuthContext";
import ThemeToggle from "../ThemeToggle";

type Props = {
  onMenuClick?: () => void;
};

export const Topbar = ({ onMenuClick }: Props) => {
  const { user } = useAuth();

  return (
    <header className="h-16 border-b border-border bg-card px-4 sm:px-6 lg:px-8 flex items-center justify-between">
      <div className="flex items-center gap-4 w-full max-w-md">
        <button
          onClick={onMenuClick}
          className="lg:hidden text-muted-foreground"
        >
          <Menu size={20} />
        </button>

        <div className="relative w-full">
          <Search
            size={16}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <input
            placeholder="Search..."
            className="w-full pl-9 pr-4 py-2 rounded-md bg-muted text-sm outline-none focus:ring-2 focus:ring-primary transition"
          />
        </div>
      </div>

      <div className="hidden sm:block text-sm text-muted-foreground">
        <ThemeToggle />
      </div>
    </header>
  );
};

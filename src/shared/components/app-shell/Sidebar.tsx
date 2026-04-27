import { NavLink, useNavigate } from "react-router-dom";
import {
  LayoutDashboard,
  Sparkles,
  CreditCard,
  Settings,
  LogOut,
  Film,
  X,
} from "lucide-react";
import { useAuth } from "../../../features/auth/context/AuthContext";
import { cn } from "@/lib/utils";

const navItems = [
  { name: "Overview", path: "/dashboard", icon: LayoutDashboard },
  { name: "Create video", path: "/dashboard/create", icon: Sparkles },
  { name: "Library", path: "/dashboard/library", icon: Film },
  { name: "Billing", path: "/dashboard/billing", icon: CreditCard },
  { name: "Settings", path: "/dashboard/settings", icon: Settings },
];

type Props = {
  mobile?: boolean;
  isOpen?: boolean;
  onClose?: () => void;
};

export const Sidebar = ({ mobile, isOpen, onClose }: Props) => {
  const navigate = useNavigate();
  const { logout } = useAuth();

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  if (mobile) {
    return (
      <>
        <div
          className={cn(
            "fixed inset-0 bg-background/60 backdrop-blur-sm z-40 transition-opacity lg:hidden",
            isOpen ? "opacity-100" : "opacity-0 pointer-events-none",
          )}
          onClick={onClose}
        />
        <aside
          className={cn(
            "fixed top-0 left-0 h-full w-64 bg-card border-r border-border z-50 transform transition-transform duration-300 lg:hidden",
            isOpen ? "translate-x-0" : "-translate-x-full",
          )}
        >
          <SidebarContent onClose={onClose} handleLogout={handleLogout} />
        </aside>
      </>
    );
  }

  return (
    <aside className="w-64 h-screen bg-card border-r border-border sticky top-0 hidden lg:flex">
      <SidebarContent handleLogout={handleLogout} />
    </aside>
  );
};

const SidebarContent = ({
  handleLogout,
  onClose,
}: {
  handleLogout: () => void;
  onClose?: () => void;
}) => {
  const { user } = useAuth();

  return (
    <div className="flex flex-col h-full w-full">
      {/* Brand */}
      <div className="px-5 py-5 border-b border-border flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="size-8 rounded-lg gradient-bg flex items-center justify-center shadow-glow">
            <Sparkles className="size-4 text-white" />
          </div>
          <div>
            <p className="text-sm font-bold leading-none">BossAI</p>
            <p className="text-[10px] text-muted-foreground mt-0.5">
              TikTok Studio
            </p>
          </div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className="lg:hidden text-muted-foreground hover:text-foreground"
          >
            <X size={18} />
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 mt-4 flex flex-col gap-0.5 px-3">
        {navItems.map(({ name, path, icon: Icon }) => (
          <NavLink
            key={name}
            to={path}
            onClick={onClose}
            end={path === "/dashboard"}
            className={({ isActive }) =>
              cn(
                "group flex items-center gap-3 px-3 py-2 rounded-lg transition-all text-sm relative",
                isActive
                  ? "bg-accent text-accent-foreground font-medium"
                  : "text-muted-foreground hover:bg-muted hover:text-foreground",
              )
            }
          >
            {({ isActive }) => (
              <>
                {isActive && (
                  <span className="absolute left-0 top-1/2 -translate-y-1/2 h-5 w-0.5 rounded-r-full gradient-bg" />
                )}
                <Icon size={16} />
                {name}
              </>
            )}
          </NavLink>
        ))}
      </nav>

      {/* User card */}
      <div className="m-3 rounded-lg border border-border bg-muted/30 p-3">
        <div className="flex items-center gap-2.5">
          <div className="size-8 rounded-full gradient-bg flex items-center justify-center text-white text-xs font-bold uppercase">
            {user?.displayName?.[0] ?? "U"}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium truncate">
              {user?.displayName ?? "—"}
            </p>
            <p className="text-[10px] text-muted-foreground truncate">
              {user?.email ?? ""}
            </p>
          </div>
          <button
            onClick={handleLogout}
            className="text-muted-foreground hover:text-destructive transition"
            title="Log out"
          >
            <LogOut size={14} />
          </button>
        </div>
      </div>
    </div>
  );
};

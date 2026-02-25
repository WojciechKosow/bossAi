import { NavLink, useNavigate } from "react-router-dom";
import {
  LayoutDashboard,
  Image,
  Video,
  CreditCard,
  Settings,
  LogOut,
  GalleryVertical,
  X,
} from "lucide-react";
import { useAuth } from "../../../features/auth/context/AuthContext";

const navItems = [
  { name: "Dashboard", path: "/dashboard", icon: LayoutDashboard },
  { name: "Generate Images", path: "/dashboard/generate/images", icon: Image },
  { name: "Generate Videos", path: "/dashboard/generate/videos", icon: Video },
  { name: "Gallery", path: "/dashboard/gallery", icon: GalleryVertical },
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
        {/* Overlay */}
        <div
          className={`fixed inset-0 bg-black/40 z-40 transition-opacity lg:hidden ${
            isOpen ? "opacity-100" : "opacity-0 pointer-events-none"
          }`}
          onClick={onClose}
        />

        {/* Drawer */}
        <aside
          className={`fixed top-0 left-0 h-full w-64 bg-card border-r border-border z-50 transform transition-transform duration-300 lg:hidden
          ${isOpen ? "translate-x-0" : "-translate-x-full"}`}
        >
          <div className="flex items-center justify-between px-6 py-6 border-b border-border">
            <span className="text-xl font-semibold">BossAI</span>
            <button onClick={onClose}>
              <X size={20} />
            </button>
          </div>

          <NavContent onClose={onClose} handleLogout={handleLogout} />
        </aside>
      </>
    );
  }

  return (
    <aside className="w-64 h-screen bg-card border-r border-border flex flex-col justify-between sticky top-0">
      <div>
        <div className="px-6 py-6 text-xl font-semibold border-b border-border">
          BossAI
        </div>
        <NavContent handleLogout={handleLogout} />
      </div>
    </aside>
  );
};

const NavContent = ({
  handleLogout,
  onClose,
}: {
  handleLogout: () => void;
  onClose?: () => void;
}) => {
  return (
    <div className="flex flex-col justify-between h-full">
      <nav className="mt-6 flex flex-col gap-1 px-3">
        {navItems.map(({ name, path, icon: Icon }) => (
          <NavLink
            key={name}
            to={path}
            onClick={onClose}
            end={path === "/dashboard"}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-md transition-all text-sm
               ${
                 isActive
                   ? "bg-primary text-primary-foreground"
                   : "text-muted-foreground hover:bg-muted hover:text-foreground"
               }`
            }
          >
            <Icon size={18} />
            {name}
          </NavLink>
        ))}
      </nav>

      <div className="p-4 border-t border-border">
        <button
          onClick={handleLogout}
          className="flex items-center gap-3 text-muted-foreground hover:text-foreground transition-all text-sm"
        >
          <LogOut size={18} />
          Logout
        </button>
      </div>
    </div>
  );
};

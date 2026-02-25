import axios from "@/lib/axios";
import {
  createContext,
  useContext,
  useState,
  useEffect,
  ReactNode,
} from "react";

type User = {
  id: string;
  displayName: string;
  email: string;
  enabled: boolean;
};

type AuthContextType = {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (token: string, user: User) => void;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider = ({ children }: { children: ReactNode }) => {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const login = (token: string, user: User) => {
    axios.defaults.headers.common["Authorization"] = `Bearer ${token}`;
    setUser(user);
  };

  const logout = async () => {
    try {
      await axios.post("/api/auth/logout");
    } catch {}
    delete axios.defaults.headers.common["Authorization"];
    setUser(null);
  };

  // 🔥 Bootstrapping session
  useEffect(() => {
    const bootstrap = async () => {
      try {
        // próbujemy refresh (cookie)
        const refreshRes = await axios.post("/api/auth/refresh");
        const accessToken = refreshRes.data.token;

        axios.defaults.headers.common["Authorization"] =
          `Bearer ${accessToken}`;

        // pobieramy usera
        const meRes = await axios.get("/api/auth/me");

        setUser(meRes.data);
      } catch {
        setUser(null);
      } finally {
        setIsLoading(false);
      }
    };

    bootstrap();
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
};

import axios from "@/lib/axios";
import {
  clearStoredToken,
  getStoredToken,
  setStoredToken,
} from "@/lib/authToken";
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
    if (token) {
      setStoredToken(token);
      axios.defaults.headers.common["Authorization"] = `Bearer ${token}`;
    }
    setUser(user);
  };

  const logout = async () => {
    try {
      await axios.post("/api/auth/logout");
    } catch {}
    clearStoredToken();
    delete axios.defaults.headers.common["Authorization"];
    setUser(null);
  };

  // Bootstrapping session on app load.
  useEffect(() => {
    const bootstrap = async () => {
      try {
        // 1) Restore from a stored access token if we have one. This survives a
        //    full page reload even when the cross-site refresh cookie is blocked
        //    by the browser (third-party cookie) — the common case when the
        //    frontend and API live on different origins (local dev → Railway).
        let accessToken = getStoredToken();
        if (accessToken) {
          axios.defaults.headers.common["Authorization"] =
            `Bearer ${accessToken}`;
        }

        // 2) Best-effort refresh to rotate the token when the cookie IS present.
        //    A 400/401 here just means "no valid refresh cookie" — not fatal as
        //    long as we still have a usable access token from step 1.
        try {
          const refreshRes = await axios.post("/api/auth/refresh");
          const rotated: string | undefined = refreshRes.data?.token;
          if (rotated) {
            accessToken = rotated;
            setStoredToken(rotated);
            axios.defaults.headers.common["Authorization"] =
              `Bearer ${rotated}`;
          }
        } catch {
          /* no/expired refresh cookie — continue with the stored token */
        }

        if (!accessToken) {
          setUser(null);
          return;
        }

        const meRes = await axios.get("/api/auth/me");
        setUser(meRes.data);
      } catch {
        // Token present but rejected by /me (and refresh couldn't renew it).
        clearStoredToken();
        delete axios.defaults.headers.common["Authorization"];
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

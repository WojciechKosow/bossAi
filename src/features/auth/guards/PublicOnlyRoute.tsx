
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export const PublicOnlyRoute = ({ children }: { children: JSX.Element }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) return null;

  if (isAuthenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return children;
};

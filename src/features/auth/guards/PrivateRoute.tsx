
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export const PrivateRoute = ({ children }: { children: JSX.Element }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) return null; // możesz tu dać loader

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return children;
};

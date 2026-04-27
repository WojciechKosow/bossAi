import { ReactElement } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export const PrivateRoute = ({ children }: { children: ReactElement }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) return null; // możesz tu dać loader

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return children;
};

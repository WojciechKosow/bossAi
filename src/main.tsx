import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "../src/app/queryClient";
import "./index.css";
import AppRouter from "./app/AppRouter";
import { AuthProvider } from "./features/auth/context/AuthContext";
import { ThemeProvider } from "./theme/ThemeProvider";
import { ToastProvider } from "./components/ui/toast";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ThemeProvider>
          <ToastProvider>
            <AppRouter />
          </ToastProvider>
        </ThemeProvider>
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);

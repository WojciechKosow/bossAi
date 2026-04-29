import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import MainLayout from "../shared/layouts/MainLayout";

import Landing from "../pages/Landing";

import LoginPage from "../features/auth/pages/LoginPage";
import RegisterPage from "../features/auth/pages/RegisterPage";
import CheckEmail from "../features/auth/pages/CheckEmail";
import VerifyPage from "../features/auth/pages/VerifyPage";
import { ForgotPasswordForm } from "../features/auth/pages/ForgotPasswordForm";
import ResetPassword from "../features/auth/pages/ResetPasswordPage";
import OAuthSuccessPage from "../features/auth/pages/OAuthSuccessPage";

import { PrivateRoute } from "../features/auth/guards/PrivateRoute";
import { PublicOnlyRoute } from "../features/auth/guards/PublicOnlyRoute";

import DashboardLayout from "../shared/layouts/DashboardLayout";
import DashboardPage from "../pages/dashboard/DashboardPage";
import CreateVideoPage from "../pages/dashboard/CreateVideoPage";
import LibraryPage from "../pages/dashboard/LibraryPage";
import ProjectEditorPage from "../pages/dashboard/ProjectEditorPage";
import GenerationPreviewPage from "../pages/dashboard/GenerationPreviewPage";
import BillingPage from "../pages/dashboard/BillingPage";
import SettingsPage from "../pages/dashboard/SettingsPage";

const AppRouter = () => {
  return (
    <BrowserRouter>
      <Routes>
        {/* PUBLIC ONLY */}
        <Route
          path="/"
          element={
            <PublicOnlyRoute>
              <MainLayout />
            </PublicOnlyRoute>
          }
        >
          <Route index element={<Landing />} />
        </Route>

        <Route
          path="/login"
          element={
            <PublicOnlyRoute>
              <LoginPage />
            </PublicOnlyRoute>
          }
        />

        <Route
          path="/register"
          element={
            <PublicOnlyRoute>
              <RegisterPage />
            </PublicOnlyRoute>
          }
        />

        <Route
          path="/forgot-password"
          element={
            <PublicOnlyRoute>
              <ForgotPasswordForm />
            </PublicOnlyRoute>
          }
        />

        <Route
          path="/reset-password"
          element={
            <PublicOnlyRoute>
              <ResetPassword />
            </PublicOnlyRoute>
          }
        />

        <Route path="/oauth-success" element={<OAuthSuccessPage />} />
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/check-email" element={<CheckEmail />} />

        {/* PRIVATE AREA */}
        <Route
          path="/dashboard"
          element={
            <PrivateRoute>
              <DashboardLayout />
            </PrivateRoute>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="create" element={<CreateVideoPage />} />
          <Route path="library" element={<LibraryPage />} />
          <Route
            path="library/preview/:id"
            element={<GenerationPreviewPage />}
          />
          <Route path="projects/:id" element={<ProjectEditorPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="settings" element={<SettingsPage />} />
          {/* legacy redirects */}
          <Route
            path="generate/images"
            element={<Navigate to="/dashboard/create" replace />}
          />
          <Route
            path="generate/videos"
            element={<Navigate to="/dashboard/create" replace />}
          />
          <Route
            path="gallery"
            element={<Navigate to="/dashboard/library" replace />}
          />
        </Route>
      </Routes>
    </BrowserRouter>
  );
};

export default AppRouter;

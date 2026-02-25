import { BrowserRouter, Routes, Route } from "react-router-dom";
import MainLayout from "../shared/layouts/MainLayout";
// import DashboardLayout from "../shared/layouts/DashboardLayout";

import Landing from "../pages/Landing";

import LoginPage from "../features/auth/pages/LoginPage";
import RegisterPage from "../features/auth/pages/RegisterPage";
import CheckEmail from "../features/auth/pages/CheckEmail";
import VerifyPage from "../features/auth/pages/VerifyPage";
import { ForgotPasswordForm } from "../features/auth/pages/ForgotPasswordForm";
import ResetPassword from "../features/auth/pages/ResetPasswordPage";

import { PrivateRoute } from "../features/auth/guards/PrivateRoute";
import { PublicOnlyRoute } from "../features/auth/guards/PublicOnlyRoute";
import DashboardLayout from "../shared/layouts/DashboardLayout";
import DashboardPage from "../pages/dashboard/DashboardPage";
import GenerateImagePage from "../pages/dashboard/GenerateImagePage";
import GalleryPage from "../pages/dashboard/GalleryPage";
import DashboardMainPage from "../pages/dashboard/DashboardMainPage";
import BillingPage from "../pages/dashboard/BillingPage";
// import DashboardLayout from "../shared/layouts/DashboardLayout";
// import DashboardMainPage from "../shared/components/dashboard/DashboardMainPage";

// import Dashboard from "../features/dashboard/pages/Dashboard";

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
          <Route path="" element={<DashboardPage />} />
          <Route path="generate/images" element={<GenerateImagePage />} />
          <Route path="gallery" element={<GalleryPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="generate/videos" element={<GenerateImagePage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
};

export default AppRouter;

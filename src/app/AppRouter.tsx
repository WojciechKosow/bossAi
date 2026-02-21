import { Route } from "react-router";
import { BrowserRouter, Routes } from "react-router";
import MainLayout from "../shared/layouts/MainLayout";
import Landing from "../pages/Landing";
import LoginPage from "../features/auth/pages/LoginPage";
import RegisterPage from "../features/auth/pages/RegisterPage";
import CheckEmail from "../features/auth/pages/CheckEmail";
import VerifyPage from "../features/auth/pages/VerifyPage";
import { ForgotPasswordForm } from "../features/auth/pages/ForgotPasswordForm";
import ResetPassword from "../features/auth/pages/ResetPasswordPage";

const AppRouter = () => {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<Landing />} />
        </Route>
        <Route element={<LoginPage />} path="/login" />
        <Route element={<RegisterPage />} path="/register" />
        <Route element={<CheckEmail />} path="/check-email" />
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordForm />} />
        <Route path="reset-password" element={<ResetPassword />} />
      </Routes>
    </BrowserRouter>
  );
};

export default AppRouter;

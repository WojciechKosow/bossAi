import AuthLayout from "@/shared/layouts/AuthLayout";
import { LoginForm } from "../components/LoginForm";

const LoginPage = () => {
  return (
    <AuthLayout title="Welcome back">
      <LoginForm />
    </AuthLayout>
  );
};

export default LoginPage;

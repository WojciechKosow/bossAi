import AuthLayout from "@/shared/layouts/AuthLayout";
import { RegisterForm } from "../components/RegisterForm";

const RegisterPage = () => {
  return (
    <AuthLayout title="Create your account">
      <RegisterForm />
    </AuthLayout>
  );
};

export default RegisterPage;

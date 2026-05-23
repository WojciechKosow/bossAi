import AuthLayout from "@/shared/layouts/AuthLayout";
import { RegisterForm } from "../components/RegisterForm";
import { BETA_MODE } from "@/lib/betaMode";

const RegisterPage = () => {
  if (BETA_MODE) {
    return (
      <AuthLayout title="Closed beta">
        <div className="text-center space-y-4">
          <p className="text-muted-foreground text-sm">
            Registration is currently closed. This is an invite-only beta.
          </p>
          <p className="text-muted-foreground text-sm">
            If you received access credentials, go to{" "}
            <a href="/login" className="text-foreground underline font-medium">
              the login page
            </a>.
          </p>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title="Create your account">
      <RegisterForm />
    </AuthLayout>
  );
};

export default RegisterPage;

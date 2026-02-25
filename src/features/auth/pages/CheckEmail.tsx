import { useState, useEffect } from "react";
import { useSearchParams } from "react-router-dom";
import AuthLayout from "@/shared/layouts/AuthLayout";
import { Button } from "@/components/ui/button";
import { useResendVerification } from "../hooks/useResendVerification";

const RESEND_COOLDOWN = 60;

const CheckEmail = () => {
  const [searchParams] = useSearchParams();
  const email = searchParams.get("email") || "";

  const resendMutation = useResendVerification();

  const [secondsLeft, setSecondsLeft] = useState(0);

  useEffect(() => {
    if (secondsLeft === 0) return;

    const timer = setInterval(() => {
      setSecondsLeft((prev) => prev - 1);
    }, 1000);

    return () => clearInterval(timer);
  }, [secondsLeft]);

  const handleResend = async () => {
    console.log(email + " email");

    if (!email) return;

    await resendMutation.mutateAsync(email);
    setSecondsLeft(RESEND_COOLDOWN);
  };

  return (
    <AuthLayout title="Check your email">
      <div className="space-y-6 text-center">
        <p className="text-muted-foreground">
          We’ve sent a verification link to:
        </p>

        <p className="font-medium">{email}</p>

        <p className="text-sm text-muted-foreground">
          If you didn’t receive the email, check your spam folder.
        </p>

        <Button
          type="button"
          onClick={handleResend}
          disabled={resendMutation.isPending || secondsLeft > 0}
          className="w-full bg-black text-white hover:bg-black"
        >
          {secondsLeft > 0
            ? `Resend in ${secondsLeft}s`
            : resendMutation.isPending
              ? "Sending..."
              : "Resend verification email"}
        </Button>

        {resendMutation.isSuccess && (
          <p className="text-sm text-green-600">
            If the email exists, a new verification link has been sent.
          </p>
        )}
      </div>
    </AuthLayout>
  );
};

export default CheckEmail;

import { useSearchParams, useNavigate } from "react-router-dom";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { resetPasswordSchema } from "../schemas";
import type { ResetPasswordFormValues } from "../schemas";
import { useResetPassword } from "../hooks/useResetPassword";
import { useState } from "react";
import AuthLayout from "@/shared/layouts/AuthLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";

const ResetPassword = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const resetMutation = useResetPassword();

  const tokenId = searchParams.get("tokenId");
  const token = searchParams.get("token");

  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [success, setSuccess] = useState(false);

  const form = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
  });

  if (!tokenId || !token) {
    return (
      <AuthLayout title="Invalid reset link">
        <p className="text-center text-muted-foreground">
          This reset link is invalid or expired.
        </p>
      </AuthLayout>
    );
  }

  const onSubmit = async (values: ResetPasswordFormValues) => {
    try {
      await resetMutation.mutateAsync({
        tokenId,
        token,
        password: values.password,
      });

      setSuccess(true);

      setTimeout(() => {
        navigate("/login");
      }, 2500);
    } catch (error) {
      form.setError("root", {
        type: "server",
        message: "Invalid or expired reset link.",
      });
    }
  };

  const password = form.watch("password") || "";

  const passwordChecks = {
    length: password.length >= 8,
    uppercase: /[A-Z]/.test(password),
    number: /\d/.test(password),
    special: /[^A-Za-z0-9]/.test(password),
  };

  return (
    <AuthLayout title="Set new password">
      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          {success ? (
            <div className="text-center space-y-4">
              <p className="text-green-600">
                Your password has been successfully changed.
              </p>
              <p className="text-sm text-muted-foreground">
                Redirecting to login...
              </p>
            </div>
          ) : (
            <>
              {form.formState.errors.root && (
                <p className="text-sm text-red-500">
                  {form.formState.errors.root.message}
                </p>
              )}

              {/* PASSWORD */}
              <FormField
                control={form.control}
                name="password"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>New password</FormLabel>
                    <div className="relative">
                      <Input
                        type={showPassword ? "text" : "password"}
                        {...field}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword((prev) => !prev)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                      >
                        {showPassword ? "Hide" : "Show"}
                      </button>
                    </div>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Password requirements */}
              <div className="text-xs space-y-1">
                <p
                  className={
                    passwordChecks.length
                      ? "text-green-600"
                      : "text-muted-foreground"
                  }
                >
                  • At least 8 characters
                </p>
                <p
                  className={
                    passwordChecks.uppercase
                      ? "text-green-600"
                      : "text-muted-foreground"
                  }
                >
                  • One uppercase letter
                </p>
                <p
                  className={
                    passwordChecks.number
                      ? "text-green-600"
                      : "text-muted-foreground"
                  }
                >
                  • One number
                </p>
                <p
                  className={
                    passwordChecks.special
                      ? "text-green-600"
                      : "text-muted-foreground"
                  }
                >
                  • One special character
                </p>
              </div>

              {/* CONFIRM PASSWORD */}
              <FormField
                control={form.control}
                name="confirmPassword"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Confirm password</FormLabel>
                    <div className="relative">
                      <Input
                        type={showConfirmPassword ? "text" : "password"}
                        {...field}
                      />
                      <button
                        type="button"
                        onClick={() => setShowConfirmPassword((prev) => !prev)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                      >
                        {showConfirmPassword ? "Hide" : "Show"}
                      </button>
                    </div>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <Button
                type="submit"
                className="w-full bg-black text-white hover:bg-black"
                disabled={resetMutation.isPending}
              >
                {resetMutation.isPending
                  ? "Updating password..."
                  : "Update password"}
              </Button>
            </>
          )}
        </form>
      </Form>
    </AuthLayout>
  );
};

export default ResetPassword;

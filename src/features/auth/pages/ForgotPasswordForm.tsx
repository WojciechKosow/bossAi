import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { forgotPasswordSchema } from "../schemas";
import type { ForgotPasswordFormValues } from "../schemas";
import { useForgotPassword } from "../hooks/useForgotPassword";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import AuthLayout from "@/shared/layouts/AuthLayout";
import { useState } from "react";
import { Link } from "react-router-dom";

export const ForgotPasswordForm = () => {
  const forgotMutation = useForgotPassword();
  const [submitted, setSubmitted] = useState(false);

  const form = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
  });

  const onSubmit = async (values: ForgotPasswordFormValues) => {
    try {
      await forgotMutation.mutateAsync(values.email);
      setSubmitted(true);
    } catch (error) {
      setSubmitted(true);
    }
  };

  return (
    <AuthLayout title="Reset your password">
      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          {!submitted ? (
            <>
              <FormField
                control={form.control}
                name="email"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Email</FormLabel>
                    <Input {...field} />
                    <FormMessage />
                  </FormItem>
                )}
              />

              <Button
                type="submit"
                className="w-full"
                disabled={forgotMutation.isPending}
              >
                {forgotMutation.isPending ? "Sending..." : "Send reset link"}
              </Button>
            </>
          ) : (
            <div className="text-center space-y-4">
              <p className="text-muted-foreground">
                If an account with that email exists, a password reset link has
                been sent.
              </p>

              <Link
                to="/login"
                className="text-sm text-primary hover:underline"
              >
                Back to login
              </Link>
            </div>
          )}
        </form>
      </Form>
    </AuthLayout>
  );
};

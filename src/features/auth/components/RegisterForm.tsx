import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { registerSchema } from "../schemas";
import type { RegisterFormValues } from "../schemas";
import { useRegister } from "../hooks/useRegister";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Form,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { useNavigate, Link } from "react-router-dom";
import { AxiosError } from "axios";
import { Loader2 } from "lucide-react";

export const RegisterForm = () => {
  const navigate = useNavigate();
  const registerMutation = useRegister();

  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const form = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
  });

  const onSubmit = async (values: RegisterFormValues) => {
    try {
      await registerMutation.mutateAsync({
        displayName: values.username,
        email: values.email,
        password: values.password,
      });

      navigate(`/check-email?email=${encodeURIComponent(values.email)}`);
    } catch (error) {
      const err = error as AxiosError<{ message?: string }>;
      const message = err.response?.data?.message;

      if (message?.toLowerCase().includes("email")) {
        form.setError("email", {
          type: "server",
          message: "Email is already in use",
        });
      } else {
        form.setError("root", {
          type: "server",
          message: "Something went wrong. Please try again.",
        });
      }
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
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
        {form.formState.errors.root && (
          <p className="text-sm text-red-500">
            {form.formState.errors.root.message}
          </p>
        )}

        <FormField
          control={form.control}
          name="username"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Username</FormLabel>
              <Input {...field} />
              <FormMessage />
            </FormItem>
          )}
        />

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

        {/* PASSWORD */}
        <FormField
          control={form.control}
          name="password"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Password</FormLabel>
              <div className="relative">
                <Input type={showPassword ? "text" : "password"} {...field} />
                <button
                  type="button"
                  onClick={() => setShowPassword((prev) => !prev)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
              <FormMessage />
              <div className="text-xs space-y-1 mt-2">
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
            </FormItem>
          )}
        />

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
          disabled={registerMutation.isPending}
        >
          {registerMutation.isPending && (
            <Loader2 className="h-4 w-4 animate-spin" />
          )}
          {registerMutation.isPending
            ? "Creating account..."
            : "Create account"}
        </Button>

        {/*   <Button
          type="submit"
          className="w-full flex items-center justify-center gap-2"
          disabled={loginMutation.isPending}
        >
          {loginMutation.isPending && (
            <Loader2 className="h-4 w-4 animate-spin" />
          )}
          {loginMutation.isPending ? "Signing in..." : "Sign in"}
        </Button>
      </form> */}

        <p className="text-sm text-center text-muted-foreground text-gray-600">
          Already have an account?{" "}
          <Link to="/login" className="font-medium text-black hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </Form>
  );
};

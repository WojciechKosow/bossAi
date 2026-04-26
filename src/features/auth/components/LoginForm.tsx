import { useState, useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { loginSchema } from "../schemas";
import type { LoginFormValues } from "../schemas";
import { useLogin } from "../hooks/useLogin";
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
import { Loader2 } from "lucide-react";
import { AxiosError } from "axios";
import { useAuth } from "../context/AuthContext";
import { GoogleLoginButton } from "./GoogleLoginButton";

export const LoginForm = () => {
  const navigate = useNavigate();
  const loginMutation = useLogin();
  const [showPassword, setShowPassword] = useState(false);

  const { login } = useAuth();

  const form = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      rememberMe: true,
    },
  });

  useEffect(() => {
    const subscription = form.watch(() => {
      if (form.formState.errors.root) {
        form.clearErrors("root");
      }
    });
    return () => subscription.unsubscribe();
  }, [form]);

  const onSubmit = async (values: LoginFormValues) => {
    try {
      const res = await loginMutation.mutateAsync(values);

      login(res.token, res.user);

      navigate("/dashboard");

      // await loginMutation.mutateAsync(values);
      // navigate("/dashboard");
    } catch (error) {
      const err = error as AxiosError<{ message?: string }>;
      const message = err.response?.data?.message?.toLowerCase();

      if (message?.includes("invalid")) {
        form.setError("root", {
          type: "server",
          message: "Invalid email or password.",
        });
      } else if (message?.includes("locked")) {
        form.setError("root", {
          type: "server",
          message: "Account temporarily locked. Try again later.",
        });
      } else {
        form.setError("root", {
          type: "server",
          message: "Something went wrong. Please try again.",
        });
      }
    }
  };

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
        {form.formState.errors.root && (
          <p className="text-sm text-red-500 text-center">
            {form.formState.errors.root.message}
          </p>
        )}

        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Email</FormLabel>
              <Input {...field} autoFocus />
              <FormMessage />
            </FormItem>
          )}
        />

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
                  onClick={() => setShowPassword((p) => !p)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
              <FormMessage />
            </FormItem>
          )}
        />

        {/* Remember + Forgot */}
        <div className="flex items-center justify-between text-sm">
          <FormField
            control={form.control}
            name="rememberMe"
            render={({ field }) => (
              <label className="flex  text-black items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={field.value}
                  onChange={field.onChange}
                  className="accent-primary cursor-pointer"
                />
                Remember me
              </label>
            )}
          />

          <button
            type="button"
            onClick={() => navigate("/forgot-password")}
            className="text-black hover:underline"
          >
            Forgot password?
          </button>
        </div>

        <div className="space-y-4">
          <GoogleLoginButton />
          <div className="flex items-center gap-2">
            <div className="h-px flex-1 bg-gray-200" />
            <span className="text-xs text-gray-400">OR</span>
            <div className="h-px flex-1 bg-gray-200" />
          </div>
        </div>

        <Button
          type="submit"
          disabled={loginMutation.isPending}
          className="w-full flex items-center justify-center gap-2 bg-black text-white hover:bg-black"
        >
          {loginMutation.isPending && (
            <Loader2 className="h-4 w-4 animate-spin" />
          )}
          {loginMutation.isPending ? "Signing in..." : "Sign in"}
        </Button>

        <p className="text-sm text-center text-gray-600">
          Don't have an account?{" "}
          <Link
            to="/register"
            className="text-black hover:underline font-medium"
          >
            Create one
          </Link>
        </p>
      </form>
    </Form>
  );
};

import { useMutation } from "@tanstack/react-query";
import { loginRequest } from "../api";
import { useAuthStore } from "../store";

export const useLogin = () => {
  const setUser = useAuthStore((s) => s.setUser);

  return useMutation({
    mutationFn: loginRequest,
    onSuccess: (data, variables) => {
      const storage = (variables as { rememberMe?: boolean }).rememberMe
        ? localStorage
        : sessionStorage;

      storage.setItem("access_token", data.token ?? "");
      setUser(data.user);
    },
  });
};
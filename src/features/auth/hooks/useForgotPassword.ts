import { useMutation } from "@tanstack/react-query";
import { forgotPassword } from "../api";

export const useForgotPassword = () => {
  return useMutation({
    mutationFn: forgotPassword,
  });
};

import { useMutation } from "@tanstack/react-query";
import { registerRequest } from "../api";

export const useRegister = () => {
  return useMutation({
    mutationFn: registerRequest,
  });
};

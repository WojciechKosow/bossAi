import { useMutation } from "@tanstack/react-query";
import { resendVerificationEmail } from "../api";

export const useResendVerification = () => {
  return useMutation({
    mutationFn: resendVerificationEmail,
  });
};

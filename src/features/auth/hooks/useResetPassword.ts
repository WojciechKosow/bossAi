import { useMutation } from "@tanstack/react-query";
import { resetPassword } from "../api";

export const useResetPassword = () => {
  return useMutation({
    mutationFn: ({
      tokenId,
      token,
      password,
    }: {
      tokenId: string;
      token: string;
      password: string;
    }) => resetPassword(tokenId, token, password),
  });
};

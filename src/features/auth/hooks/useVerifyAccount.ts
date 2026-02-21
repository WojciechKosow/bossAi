import { useMutation } from "@tanstack/react-query";
import { verifyAccount } from "../api";

export const useVerifyAccount = () => {
  return useMutation({
    mutationFn: ({ tokenId, token }: { tokenId: string; token: string }) =>
      verifyAccount(tokenId, token),
  });
};

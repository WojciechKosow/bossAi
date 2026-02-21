import axios from "@/shared/api/axios";

interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthResponse {
  token: string | null;
  user: {
    id: string;
    displayName: string;
    email: string;
    enabled: boolean;
  };
}

export const loginRequest = async (
  data: LoginRequest,
): Promise<AuthResponse> => {
  const response = await axios.post(
    "http://localhost:8080/api/auth/login",
    data,
  );
  return response.data;
};

interface RegisterRequest {
  displayName: string;
  email: string;
  password: string;
}

export const registerRequest = async (data: RegisterRequest) => {
  const response = await axios.post(
    "http://localhost:8080/api/auth/register",
    data,
  );
  return response.data;
};

export const resendVerificationEmail = async (email: string) => {
  const { data } = await axios.post(
    "http://localhost:8080/api/auth/resend-verification-email",
    { email },
  );

  return data;
};

export const verifyAccount = async (tokenId: string, token: string) => {
  const { data } = await axios.get("http://localhost:8080/api/auth/verify", {
    params: { tokenId, token },
  });

  return data;
};

export const forgotPassword = async (email: string) => {
  const res = await axios.post(
    "http://localhost:8080/api/auth/forgot-password",
    { email },
  );
  return res.data;
};

export const resetPassword = async (
  tokenId: string,
  token: string,
  password: string,
) => {
  const res = await axios.post(
    `http://localhost:8080/api/auth/reset-password?tokenId=${tokenId}&token=${token}`,
    {
      newPassword: password,
    },
  );

  return res.data;
};

import { useEffect, useState } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import AuthLayout from "@/shared/layouts/AuthLayout";
import { useVerifyAccount } from "../hooks/useVerifyAccount";
import { Button } from "@/components/ui/button";

const VerifyPage = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const verifyMutation = useVerifyAccount();

  const tokenId = searchParams.get("tokenId");
  const token = searchParams.get("token");

  const [status, setStatus] = useState<"loading" | "success" | "error">(
    "loading",
  );

  useEffect(() => {
    if (!tokenId || !token) {
      setStatus("error");
      return;
    }

    verifyMutation
      .mutateAsync({ tokenId, token })
      .then(() => setStatus("success"))
      .catch(() => setStatus("error"));
  }, []);

  return (
    <AuthLayout title="Account verification">
      <div className="text-center space-y-6">
        {status === "loading" && (
          <p className="text-muted-foreground">Verifying your account...</p>
        )}

        {status === "success" && (
          <>
            <p className="text-green-600 font-medium">
              Your account has been activated 🎉
            </p>
            <Button onClick={() => navigate("/login")}>Go to login</Button>
          </>
        )}

        {status === "error" && (
          <>
            <p className="text-red-600 font-medium">
              Invalid or expired verification link.
            </p>
            <Button onClick={() => navigate("/register")}>
              Register again
            </Button>
          </>
        )}
      </div>
    </AuthLayout>
  );
};

export default VerifyPage;

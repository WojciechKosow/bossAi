import { useEffect } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import axios from "@/lib/axios";
import { useAuth } from "../context/AuthContext";

const OAuthSuccessPage = () => {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();

  useEffect(() => {
    const token = params.get("token");

    if (!token) {
      navigate("/login");
      return;
    }

    // Fetch the user via the shared axios instance so it hits the configured
    // API origin (not a hardcoded localhost) with credentials.
    axios
      .get("/api/auth/me", {
        headers: { Authorization: `Bearer ${token}` },
      })
      .then((res) => {
        login(token, res.data);
        navigate("/dashboard");
      })
      .catch(() => {
        navigate("/login");
      });
  }, []);

  return <p className="text-center mt-10">Signing you in...</p>;
};

export default OAuthSuccessPage;

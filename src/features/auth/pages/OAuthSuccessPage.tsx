import { useEffect } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
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

    // 🔥 pobierz usera (ważne)
    fetch("http://localhost:8080/api/auth/me", {
      headers: {
        Authorization: `Bearer ${token}`,
      },
      credentials: "include",
    })
      .then((res) => res.json())
      .then((user) => {
        login(token, user);
        navigate("/dashboard");
      })
      .catch(() => {
        navigate("/login");
      });
  }, []);

  return <p className="text-center mt-10">Signing you in...</p>;
};

export default OAuthSuccessPage;

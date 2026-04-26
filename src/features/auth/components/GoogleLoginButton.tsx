import { Button } from "@/components/ui/button";

export const GoogleLoginButton = () => {
  const handleLogin = () => {
    window.location.href = "http://localhost:8080/oauth2/authorization/google";
  };

  return (
    <Button
      type="button"
      onClick={handleLogin}
      className="w-full flex items-center justify-center gap-2 border border-gray-300 bg-white text-black hover:bg-gray-50"
    >
      <img
        src="https://www.svgrepo.com/show/475656/google-color.svg"
        alt="Google"
        className="w-5 h-5"
      />
      Continue with Google
    </Button>
  );
};

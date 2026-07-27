import { Button } from "@/components/ui/button";
import { baseURL } from "@/lib/axios";

export const GoogleLoginButton = () => {
  const handleLogin = () => {
    window.location.href = `${baseURL}/oauth2/authorization/google`;
  };

  return (
    <Button
      type="button"
      onClick={handleLogin}
      className="w-full flex items-center justify-center gap-2 border border-border bg-card text-foreground hover:bg-muted"
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

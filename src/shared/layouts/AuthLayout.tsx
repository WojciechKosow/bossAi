import { Link } from "react-router-dom";
import { BrandLogo } from "@/shared/components/brand/BrandLogo";

type Props = {
  children: React.ReactNode;
  title: string;
};

const AuthLayout = ({ children, title }: Props) => {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-6">
      {/* SUBTLE BACKGROUND GLOW */}
      <div className="absolute inset-0 -z-10 overflow-hidden">
        <div className="absolute w-[600px] h-[600px] bg-white/10 rounded-full blur-3xl top-[-200px] left-1/2 -translate-x-1/2" />
      </div>

      {/* CARD */}
      <div className="w-full max-w-md bg-white p-8 rounded-2xl shadow-sm border border-gray-200">
        {/* LOGO */}
        <div className="flex justify-center mb-6">
          <Link to="/">
            <BrandLogo tone="dark" iconClassName="size-12" textClassName="text-lg" />
          </Link>
        </div>

        {/* TITLE */}
        <h1 className="text-2xl font-semibold text-center mb-6">{title}</h1>

        {children}
      </div>
    </div>
  );
};

export default AuthLayout;

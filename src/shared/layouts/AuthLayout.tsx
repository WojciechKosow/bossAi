import { Link } from "react-router-dom";

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
          <Link to="/" className="flex items-center gap-2">
            <div className="w-9 h-9 rounded-xl bg-gray-900 flex items-center justify-center text-white font-semibold">
              T
            </div>
            <span className="text-lg font-semibold">Toucan Ai</span>
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

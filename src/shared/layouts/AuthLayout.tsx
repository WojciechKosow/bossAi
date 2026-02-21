import { Link } from "react-router";

const AuthLayout = ({ children, title }) => {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-6">
      <div className="w-full max-w-md bg-white p-8 rounded-2xl shadow-sm border border-gray-200">
        <div className="flex justify-center mb-6">
          <Link to="/" className="flex items-center gap-2">
            <div className="w-9 h-9 rounded-xl bg-gray-900 flex items-center justify-center text-white font-semibold">
              T
            </div>
            <span className="text-lg font-semibold">Toucan Ai</span>
          </Link>
        </div>
        <h1 className="text-2xl font-semibold text-center mb-6">{title}</h1>

        {children}
      </div>
    </div>
  );
};

export default AuthLayout;

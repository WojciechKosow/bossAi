import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useState,
} from "react";
import { AnimatePresence, motion } from "framer-motion";
import { CheckCircle2, AlertCircle, Info, X } from "lucide-react";

type ToastVariant = "success" | "error" | "info";

type Toast = {
  id: string;
  title?: string;
  description?: string;
  variant: ToastVariant;
};

type ToastContextValue = {
  toast: (
    payload: Omit<Toast, "id" | "variant"> & { variant?: ToastVariant },
  ) => void;
  success: (description: string, title?: string) => void;
  error: (description: string, title?: string) => void;
  info: (description: string, title?: string) => void;
};

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

const variantStyles: Record<
  ToastVariant,
  { icon: typeof CheckCircle2; ring: string; iconClass: string }
> = {
  success: {
    icon: CheckCircle2,
    ring: "border-emerald-500/30",
    iconClass: "text-emerald-500",
  },
  error: {
    icon: AlertCircle,
    ring: "border-destructive/40",
    iconClass: "text-destructive",
  },
  info: {
    icon: Info,
    ring: "border-primary/30",
    iconClass: "text-primary",
  },
};

export const ToastProvider = ({ children }: { children: ReactNode }) => {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const remove = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (payload: Omit<Toast, "id" | "variant"> & { variant?: ToastVariant }) => {
      const id = Math.random().toString(36).slice(2);
      const variant = payload.variant ?? "info";
      setToasts((prev) => [...prev, { id, variant, ...payload }]);
      window.setTimeout(() => remove(id), 4500);
    },
    [remove],
  );

  const value: ToastContextValue = {
    toast: push,
    success: (description, title) =>
      push({ description, title, variant: "success" }),
    error: (description, title) =>
      push({ description, title, variant: "error" }),
    info: (description, title) =>
      push({ description, title, variant: "info" }),
  };

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-6 right-6 z-[100] flex w-full max-w-sm flex-col gap-3">
        <AnimatePresence initial={false}>
          {toasts.map((t) => {
            const v = variantStyles[t.variant];
            const Icon = v.icon;
            return (
              <motion.div
                key={t.id}
                initial={{ opacity: 0, y: 16, scale: 0.96 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, x: 24, scale: 0.96 }}
                transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
                className={`pointer-events-auto glass border ${v.ring} rounded-xl px-4 py-3 shadow-elev`}
              >
                <div className="flex items-start gap-3">
                  <Icon size={18} className={`mt-0.5 ${v.iconClass}`} />
                  <div className="flex-1 min-w-0">
                    {t.title && (
                      <p className="text-sm font-medium text-foreground">
                        {t.title}
                      </p>
                    )}
                    {t.description && (
                      <p className="text-xs text-muted-foreground mt-0.5">
                        {t.description}
                      </p>
                    )}
                  </div>
                  <button
                    onClick={() => remove(t.id)}
                    className="text-muted-foreground hover:text-foreground transition"
                  >
                    <X size={14} />
                  </button>
                </div>
              </motion.div>
            );
          })}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  );
};

export const useToast = () => {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
};

import { useState } from "react";
import emailjs from "@emailjs/browser";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, CheckCircle2, Send } from "lucide-react";

const SERVICE_ID = import.meta.env.VITE_EMAILJS_SERVICE_ID ?? "";
const TEMPLATE_ID = import.meta.env.VITE_EMAILJS_TEMPLATE_ID ?? "";
const PUBLIC_KEY = import.meta.env.VITE_EMAILJS_PUBLIC_KEY ?? "";

type State = "idle" | "loading" | "success" | "error";

const BetaSignupForm = () => {
  const [state, setState] = useState<State>("idle");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim()) return;

    if (!SERVICE_ID || !TEMPLATE_ID || !PUBLIC_KEY) {
      console.error("[BetaSignupForm] EmailJS env vars not set.");
      setState("error");
      return;
    }

    setState("loading");
    try {
      await emailjs.send(
        SERVICE_ID,
        TEMPLATE_ID,
        { name: name.trim() || "—", email: email.trim(), reply_to: email.trim() },
        PUBLIC_KEY
      );
      setState("success");
    } catch (err) {
      console.error("[BetaSignupForm] EmailJS error:", err);
      setState("error");
    }
  };

  return (
    <AnimatePresence mode="wait">
      {state === "success" ? (
        <motion.div
          key="success"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          className="flex flex-col items-center gap-4 py-6"
        >
          <CheckCircle2 className="size-14 text-green-500" />
          <h3 className="text-xl font-semibold">You're on the list!</h3>
          <p className="text-muted-foreground text-sm text-center max-w-xs">
            We'll reach out with your access credentials soon. Keep an eye on your inbox.
          </p>
        </motion.div>
      ) : (
        <motion.form
          key="form"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          onSubmit={handleSubmit}
          className="w-full max-w-sm mx-auto space-y-3"
        >
          <input
            type="text"
            placeholder="Your name (optional)"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={state === "loading"}
            className="w-full px-4 py-3 rounded-xl border border-border bg-background text-sm outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground disabled:opacity-50 transition"
          />
          <input
            type="email"
            placeholder="Your email address"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={state === "loading"}
            className="w-full px-4 py-3 rounded-xl border border-border bg-background text-sm outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground disabled:opacity-50 transition"
          />

          {state === "error" && (
            <p className="text-sm text-red-500 text-center">
              Something went wrong. Please try again or contact us directly.
            </p>
          )}

          <button
            type="submit"
            disabled={state === "loading" || !email.trim()}
            className="w-full flex items-center justify-center gap-2 px-6 py-3 rounded-xl gradient-bg text-white font-semibold shadow-glow hover:opacity-90 transition disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {state === "loading" ? (
              <><Loader2 className="size-4 animate-spin" /> Sending...</>
            ) : (
              <><Send className="size-4" /> Request beta access</>
            )}
          </button>

          <p className="text-xs text-muted-foreground text-center pt-1">
            No spam. Just your access credentials when you're approved.
          </p>
        </motion.form>
      )}
    </AnimatePresence>
  );
};

export default BetaSignupForm;

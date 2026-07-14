// Re-export the single shared axios instance so every feature talks to the same
// API origin with the same credential + bearer handling. Previously this file
// created a second instance pointed at a *different* env var (VITE_API_URL),
// which meant login and session-refresh could hit different origins and the
// refresh cookie set at login was never sent back.
export { default } from "@/lib/axios";

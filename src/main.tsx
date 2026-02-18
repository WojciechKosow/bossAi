import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import "@/index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <div className="p-8 text-xl">money</div>
  </StrictMode>,
);

import { Link } from "react-router-dom";
import { XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";

/** Shown when the user backs out of Stripe Checkout. Nothing was charged. */
const BillingCancelPage = () => (
  <div className="min-h-screen flex items-center justify-center p-6">
    <div className="max-w-md w-full bg-card border border-border rounded-xl p-8 text-center space-y-5">
      <XCircle className="size-12 mx-auto text-muted-foreground" />
      <h1 className="text-xl font-semibold">Checkout cancelled</h1>
      <p className="text-muted-foreground text-sm">
        No worries — you have not been charged. You can pick a plan or credit
        pack whenever you're ready.
      </p>
      <Button asChild className="w-full">
        <Link to="/dashboard/billing">Back to billing</Link>
      </Button>
    </div>
  </div>
);

export default BillingCancelPage;

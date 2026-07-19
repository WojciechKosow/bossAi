import { Link, useSearchParams } from "react-router-dom";
import { CheckCircle2, Loader2, XCircle } from "lucide-react";
import { useOrderStatus } from "@/features/billing/hooks";
import { Button } from "@/components/ui/button";

/**
 * Landing page after Stripe Checkout. It does NOT grant anything — fulfilment
 * happens in the backend webhook. This page just polls the order until the
 * backend confirms it, so it's correct even if the user closed the tab during
 * payment and came back later.
 */
const BillingSuccessPage = () => {
  const [params] = useSearchParams();
  const orderId = params.get("order");
  const { data: order } = useOrderStatus(orderId);

  const status = order?.status;
  const settled =
    status === "PAID" || status === "FAILED" || status === "EXPIRED";

  return (
    <div className="min-h-screen flex items-center justify-center p-6">
      <div className="max-w-md w-full bg-card border border-border rounded-xl p-8 text-center space-y-5">
        {!orderId && (
          <>
            <XCircle className="size-12 mx-auto text-destructive" />
            <h1 className="text-xl font-semibold">Missing order reference</h1>
            <p className="text-muted-foreground text-sm">
              We couldn't find your order. If you were charged, it will still be
              applied automatically.
            </p>
          </>
        )}

        {orderId && !settled && (
          <>
            <Loader2 className="size-12 mx-auto animate-spin text-primary" />
            <h1 className="text-xl font-semibold">Confirming your payment…</h1>
            <p className="text-muted-foreground text-sm">
              This only takes a few seconds. You can safely wait here.
            </p>
          </>
        )}

        {status === "PAID" && (
          <>
            <CheckCircle2 className="size-12 mx-auto text-green-500" />
            <h1 className="text-xl font-semibold">Payment confirmed 🎉</h1>
            <p className="text-muted-foreground text-sm">
              {order?.purpose === "TOP_UP"
                ? `${order.credits} credits have been added to your wallet.`
                : "Your plan is now active."}
            </p>
            <Button asChild className="w-full">
              <Link to="/dashboard/billing">Back to billing</Link>
            </Button>
          </>
        )}

        {(status === "FAILED" || status === "EXPIRED") && (
          <>
            <XCircle className="size-12 mx-auto text-destructive" />
            <h1 className="text-xl font-semibold">Payment didn't go through</h1>
            <p className="text-muted-foreground text-sm">
              You have not been charged. Please try again.
            </p>
            <Button asChild variant="outline" className="w-full">
              <Link to="/dashboard/billing">Back to billing</Link>
            </Button>
          </>
        )}
      </div>
    </div>
  );
};

export default BillingSuccessPage;

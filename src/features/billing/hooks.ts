import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  cancelSubscription,
  getActivePlan,
  getCreditPacks,
  getCredits,
  getOrder,
  getPlans,
  openBillingPortal,
  resumeSubscription,
  startPlanCheckout,
  startSubscription,
  startTopUp,
} from "./api";
import type { OrderStatusValue } from "./types";

// Checkout mutations redirect the browser to Stripe's hosted page on success.
const redirectToCheckout = (url: string) => {
  if (url) window.location.href = url;
};

export const useCreditPacks = () =>
  useQuery({ queryKey: ["credit-packs"], queryFn: getCreditPacks });

export const usePlansCatalog = () =>
  useQuery({ queryKey: ["plans-catalog"], queryFn: getPlans });

export const useActivePlan = () =>
  useQuery({ queryKey: ["active-plan"], queryFn: getActivePlan });

export const useCredits = () =>
  useQuery({ queryKey: ["credits"], queryFn: getCredits });

export const useStartTopUp = () =>
  useMutation({
    mutationFn: startTopUp,
    onSuccess: (r) => redirectToCheckout(r.checkoutUrl),
  });

export const useStartPlan = () =>
  useMutation({
    mutationFn: startPlanCheckout,
    onSuccess: (r) => redirectToCheckout(r.checkoutUrl),
  });

export const useStartSubscription = () =>
  useMutation({
    mutationFn: startSubscription,
    onSuccess: (r) => redirectToCheckout(r.checkoutUrl),
  });

export const useBillingPortal = () =>
  useMutation({
    mutationFn: openBillingPortal,
    onSuccess: (r) => redirectToCheckout(r.url),
  });

export const useCancelSubscription = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: cancelSubscription,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["active-plan"] }),
  });
};

export const useResumeSubscription = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: resumeSubscription,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["active-plan"] }),
  });
};

const SETTLED: OrderStatusValue[] = ["PAID", "FAILED", "EXPIRED"];

/**
 * Polls a payment order until it settles. Fulfilment happens server-side in the
 * Stripe webhook, so this just reflects the backend's truth — it works even if
 * the user closed and reopened the tab.
 */
export const useOrderStatus = (orderId: string | null) =>
  useQuery({
    queryKey: ["order", orderId],
    queryFn: () => getOrder(orderId as string),
    enabled: !!orderId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && SETTLED.includes(status) ? false : 2000;
    },
  });

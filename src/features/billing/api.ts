import axios from "@/lib/axios";
import type {
  ActivePlan,
  CheckoutResult,
  CreditPack,
  OrderStatus,
  PlanDefinition,
  PlanType,
  SubscriptionState,
} from "./types";

/** Wallet top-up SKUs available for purchase. */
export const getCreditPacks = async (): Promise<CreditPack[]> => {
  const { data } = await axios.get("/api/payments/credit-packs");
  return data;
};

/** Start a hosted-Checkout session to buy a wallet credit pack. */
export const startTopUp = async (pack: string): Promise<CheckoutResult> => {
  const { data } = await axios.post("/api/payments/checkout/top-up", { pack });
  return data;
};

/** Start a hosted-Checkout session for a one-time plan (TRIAL). */
export const startPlanCheckout = async (
  planType: PlanType,
): Promise<CheckoutResult> => {
  const { data } = await axios.post("/api/payments/checkout/plan", { planType });
  return data;
};

/** Start a hosted-Checkout subscription session (BASIC / PRO). */
export const startSubscription = async (
  planType: PlanType,
): Promise<CheckoutResult> => {
  const { data } = await axios.post("/api/payments/checkout/subscription", {
    planType,
  });
  return data;
};

/** Cancel the active subscription at period end (keeps the plan until it expires). */
export const cancelSubscription = async (): Promise<SubscriptionState> => {
  const { data } = await axios.post("/api/payments/subscription/cancel");
  return data;
};

/** Undo a pending cancellation. */
export const resumeSubscription = async (): Promise<SubscriptionState> => {
  const { data } = await axios.post("/api/payments/subscription/resume");
  return data;
};

/** Open the Stripe billing portal (manage / cancel subscription, update card). */
export const openBillingPortal = async (): Promise<{ url: string }> => {
  const { data } = await axios.post("/api/payments/portal");
  return data;
};

/** Poll the real outcome of a checkout — never trust the browser redirect. */
export const getOrder = async (orderId: string): Promise<OrderStatus> => {
  const { data } = await axios.get(`/api/payments/orders/${orderId}`);
  return data;
};

/** Full plan catalog (used to render subscription / one-time plan cards). */
export const getPlans = async (): Promise<PlanDefinition[]> => {
  const { data } = await axios.get("/api/plans");
  return data;
};

/** The user's current active plan. */
export const getActivePlan = async (): Promise<ActivePlan | null> => {
  const { data } = await axios.get("/api/me/plans/active-plan");
  return data;
};

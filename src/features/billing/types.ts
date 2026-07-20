export type PlanType =
  | "FREE"
  | "TRIAL"
  | "STARTER"
  | "BASIC"
  | "PRO"
  | "CREATOR";

export type PaymentPurpose = "TOP_UP" | "PLAN" | "SUBSCRIPTION";

export type OrderStatusValue =
  | "CREATED"
  | "PENDING"
  | "PAID"
  | "FAILED"
  | "EXPIRED";

export interface CheckoutResult {
  orderId: string;
  checkoutUrl: string;
}

export interface CreditPack {
  id: string; // e.g. "PACK_500"
  credits: number;
  priceCents: number;
  displayName: string;
}

export interface OrderStatus {
  orderId: string;
  status: OrderStatusValue;
  purpose: PaymentPurpose;
  planType: PlanType | null;
  credits: number;
  amountCents: number;
  currency: string;
}

export interface PlanDefinition {
  id: PlanType;
  monthlyCreditsTotal: number;
  maxConcurrentGenerations: number;
  watermark: boolean;
  commercialUse: boolean;
  priorityQueue: boolean;
  storage: boolean;
  assetReuse: boolean;
  oneTime: boolean;
  subscription: boolean;
  durationDays: number;
  priceCents: number;
  currency: string;
}

export interface ActivePlan {
  id: string;
  type: PlanType;
  activatedAt: string;
  expiresAt: string;
  active: boolean;
}

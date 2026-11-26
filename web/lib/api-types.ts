// Wire types from api/contract/openapi.yaml. Money arrives as a JSON number.
import type { Status } from "./format";

export type Unit = "pack" | "kg" | "pc";

export interface BusinessView {
  id: number;
  name: string;
  cutoff: string;
  deliveryDays: number[];
}

export interface BusinessInput {
  name: string;
  cutoff: string;
  deliveryDays: number[];
}

export interface MeView {
  subject: string;
  user: { id: number; displayName: string | null; email: string | null };
  tenant: BusinessView | null;
}

export interface ProductView {
  id: number;
  name: string;
  unit: Unit;
  price: number;
  active: boolean;
}

export interface ProductInput {
  name: string;
  unit: Unit;
  price: number;
  active?: boolean;
}

export interface ResellerView {
  id: number;
  name: string;
  mobile: string;
  status: "invited" | "active" | "blocked";
  token: string;
  orderLink: string;
  lastOrderAt: string | null;
}

export interface OrderItemView {
  productName: string;
  unit: Unit;
  quantity: number;
}

export interface OrderSummaryView {
  id: number;
  number: number;
  resellerId: number;
  resellerName: string;
  placedAt: string;
  deliveryDate: string;
  status: Status;
  total: number;
  paid: boolean;
  note: string | null;
  /** Lines in the order they were entered. */
  items: OrderItemView[];
}

export interface OrderLineView {
  productId: number;
  productName: string;
  unit: string;
  quantity: number;
  price: number;
  total: number;
}

export interface OrderView extends Omit<OrderSummaryView, "items"> {
  confirmedAt: string | null;
  deliveredAt: string | null;
  cancelledAt: string | null;
  paidAt: string | null;
  lines: OrderLineView[];
}

export interface CatalogProductView {
  id: number;
  name: string;
  unit: Unit;
  price: number;
}

export interface RecentOrderView {
  id: number;
  number: number;
  deliveryDate: string;
  total: number;
  status: Status;
  placedAt: string;
}

export interface CatalogView {
  businessName: string;
  resellerName: string;
  deliveryDate: string;
  cutoff: string;
  products: CatalogProductView[];
  recentOrders: RecentOrderView[];
}

export interface ProductionRunView {
  date: string;
  lines: {
    productId: number;
    productName: string;
    unit: string;
    quantity: number;
    orderCount: number;
  }[];
  unconfirmedCount: number;
}

export interface SettingsPreviewView {
  /** Delivery date of an order placed now with these settings. */
  deliveryDate: string;
  timeZone: string;
}

/**
 * application/problem+json from the API. `errors` maps a field's JSON path (`price`,
 * `lines[0].quantity`) to a message; it is empty when no single field is at fault.
 */
export interface Problem {
  status: number;
  title?: string;
  detail?: string;
  errors?: Record<string, string>;
}

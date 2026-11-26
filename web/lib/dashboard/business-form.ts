import type { BusinessInput } from "../api-types";

export const WEEKDAYS = [
  { day: 1, label: "Mon" },
  { day: 2, label: "Tue" },
  { day: 3, label: "Wed" },
  { day: 4, label: "Thu" },
  { day: 5, label: "Fri" },
  { day: 6, label: "Sat" },
  { day: 7, label: "Sun" },
];

export type BusinessErrors = Partial<Record<"name" | "cutoff" | "deliveryDays", string>>;

/** Reads and checks the set-up and settings form (functional spec 3.1). */
export function readBusinessForm(data: FormData): { input: BusinessInput; errors: BusinessErrors } {
  const name = String(data.get("name") ?? "").trim();
  const cutoff = String(data.get("cutoff") ?? "");
  const deliveryDays = data.getAll("deliveryDays").map(Number).filter((d) => d >= 1 && d <= 7);
  const errors: BusinessErrors = {};
  if (!name) errors.name = "Enter your business name.";
  else if (name.length > 80) errors.name = "Use 80 characters or fewer.";
  if (!/^\d{2}:\d{2}(:\d{2})?$/.test(cutoff)) errors.cutoff = "Enter a cut-off time.";
  if (deliveryDays.length === 0) errors.deliveryDays = "Choose at least one delivery day.";
  return { input: { name, cutoff: cutoff.slice(0, 5), deliveryDays }, errors };
}

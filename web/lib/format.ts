// Dates and money for display: Intl in en-PH and the tenant's timezone, from values the API
// returns. Nothing here derives a business date (interface design, section 11).

/** v1 fixes every business to Asia/Manila (functional spec 3.1). */
export const TIME_ZONE = "Asia/Manila";

const money = new Intl.NumberFormat("en-PH", {
  style: "currency",
  currency: "PHP",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** `₱1,234.50` */
export function formatMoney(value: number): string {
  return money.format(value);
}

// A delivery date is a calendar date with no zone; format it in UTC so it never shifts a day.
function calendarDate(iso: string): Date {
  return new Date(`${iso}T00:00:00Z`);
}

const short = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  timeZone: "UTC",
});
const long = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  year: "numeric",
  timeZone: "UTC",
});

/** `Tue, 4 Nov` */
export function formatShortDate(iso: string): string {
  const parts = short.formatToParts(calendarDate(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("weekday")}, ${get("day")} ${get("month")}`;
}

/** `Wed, 5 Nov 2026` */
export function formatLongDate(iso: string): string {
  const parts = long.formatToParts(calendarDate(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("weekday")}, ${get("day")} ${get("month")} ${get("year")}`;
}

/** `16:00:00` or `16:00` to `4:00 PM` */
export function formatClock(clock: string): string {
  const [h, m] = clock.split(":").map(Number);
  const hour = h % 12 === 0 ? 12 : h % 12;
  return `${hour}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
}

const timeOfDay = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: TIME_ZONE,
});
const weekday = new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: TIME_ZONE });
const dayKey = new Intl.DateTimeFormat("en-CA", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  timeZone: TIME_ZONE,
});

/** The calendar date of an instant in Manila, as `YYYY-MM-DD`. */
export function manilaDate(instant: Date): string {
  return dayKey.format(instant);
}

/** `9:12 AM` when placed today, else `Tue 3:40 PM` (interface design 6.3). */
export function formatPlaced(iso: string, now: Date = new Date()): string {
  const at = new Date(iso);
  const time = timeOfDay.format(at);
  return manilaDate(at) === manilaDate(now) ? time : `${weekday.format(at)} ${time}`;
}

/** `Wed 8:51 AM`, for order history lines. */
export function formatDayTime(iso: string): string {
  const at = new Date(iso);
  return `${weekday.format(at)} ${timeOfDay.format(at)}`;
}

/** `+639171234567` to `0917 123 4567` (functional spec 5.4). Anything else is shown as is. */
export function formatMobile(stored: string): string {
  const match = /^\+63(\d{3})(\d{3})(\d{4})$/.exec(stored);
  return match ? `0${match[1]} ${match[2]} ${match[3]}` : stored;
}

export type Status = "placed" | "confirmed" | "delivered" | "cancelled";

export const STATUS_LABEL: Record<Status, string> = {
  placed: "Placed",
  confirmed: "Confirmed",
  delivered: "Delivered",
  cancelled: "Cancelled",
};

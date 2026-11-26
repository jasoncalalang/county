"use client";
import { formatLongDate } from "@/lib/format";
import { stepDeliveryDay } from "@/lib/dashboard/orders";
import ui from "../../ui.module.css";

/** ‹ Wed, 5 Nov 2026 › plus a native date input; arrows step over non-delivery days. */
export function DatePicker({
  date,
  deliveryDays,
  onChange,
}: {
  date: string;
  deliveryDays: number[];
  onChange: (date: string) => void;
}) {
  return (
    <div className={ui.datePicker}>
      <span className={`${ui.label} ${ui.muted}`}>Delivery date</span>
      <button
        type="button"
        className={ui.arrow}
        aria-label="Previous delivery day"
        onClick={() => onChange(stepDeliveryDay(date, deliveryDays, -1))}
      >
        ‹
      </button>
      <span className={ui.dateLabel} aria-live="polite">
        {formatLongDate(date)}
      </span>
      <button
        type="button"
        className={ui.arrow}
        aria-label="Next delivery day"
        onClick={() => onChange(stepDeliveryDay(date, deliveryDays, 1))}
      >
        ›
      </button>
      <input
        type="date"
        className={ui.dateInput}
        aria-label="Choose a delivery date"
        value={date}
        onChange={(e) => e.target.value && onChange(e.target.value)}
      />
    </div>
  );
}

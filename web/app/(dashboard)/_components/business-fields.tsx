"use client";
import type { BusinessInput } from "@/lib/api-types";
import { WEEKDAYS, type BusinessErrors } from "@/lib/dashboard/business-form";
import ui from "../../ui.module.css";

/** Business name, cut-off and delivery days: shared by set-up and settings. */
export function BusinessFields({
  initial,
  errors,
  onChange,
}: {
  initial: BusinessInput;
  errors: BusinessErrors;
  onChange?: () => void;
}) {
  return (
    <>
      <div className={ui.field}>
        <label htmlFor="business-name" className={ui.label}>
          Business name
        </label>
        <input
          id="business-name"
          name="name"
          defaultValue={initial.name}
          maxLength={80}
          aria-invalid={Boolean(errors.name)}
          onChange={onChange}
        />
        {errors.name && <span className={ui.fieldError}>{errors.name}</span>}
      </div>
      <div className={ui.field}>
        <label htmlFor="business-cutoff" className={ui.label}>
          Cut-off time
        </label>
        <input
          id="business-cutoff"
          name="cutoff"
          type="time"
          defaultValue={initial.cutoff.slice(0, 5)}
          aria-invalid={Boolean(errors.cutoff)}
          aria-describedby="business-cutoff-hint"
          onChange={onChange}
        />
        <span id="business-cutoff-hint" className={errors.cutoff ? ui.fieldError : ui.small}>
          {errors.cutoff ?? "Orders placed at or after this time ship a day later."}
        </span>
      </div>
      <fieldset className={ui.days} aria-describedby={errors.deliveryDays ? "business-days-error" : undefined}>
        <legend className={ui.label}>Delivery days</legend>
        {WEEKDAYS.map(({ day, label }) => (
          <label key={day} className={ui.day}>
            <input
              type="checkbox"
              name="deliveryDays"
              value={day}
              defaultChecked={initial.deliveryDays.includes(day)}
              onChange={onChange}
            />
            {label}
          </label>
        ))}
        {errors.deliveryDays && (
          <span id="business-days-error" className={ui.fieldError} style={{ flexBasis: "100%" }}>
            {errors.deliveryDays}
          </span>
        )}
      </fieldset>
      <p className={ui.small}>Timezone Asia/Manila · Currency PHP</p>
    </>
  );
}

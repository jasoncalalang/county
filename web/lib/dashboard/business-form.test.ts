import { expect, test } from "vitest";
import { readBusinessForm } from "./business-form";
import { shareMessage } from "./share";

function form(entries: [string, string][]) {
  const data = new FormData();
  for (const [k, v] of entries) data.append(k, v);
  return data;
}

test("reads name, cut-off and the checked days", () => {
  const { input, errors } = readBusinessForm(
    form([["name", " Nena's Longganisa "], ["cutoff", "16:00"], ["deliveryDays", "1"], ["deliveryDays", "3"]]),
  );
  expect(errors).toEqual({});
  expect(input).toEqual({ name: "Nena's Longganisa", cutoff: "16:00", deliveryDays: [1, 3] });
});

test("needs a name of 1 to 80 characters and at least one day", () => {
  expect(readBusinessForm(form([["name", " "], ["cutoff", "16:00"]])).errors).toEqual({
    name: "Enter your business name.",
    deliveryDays: "Choose at least one delivery day.",
  });
  expect(readBusinessForm(form([["name", "x".repeat(81)], ["cutoff", "16:00"], ["deliveryDays", "1"]])).errors.name).toBe(
    "Use 80 characters or fewer.",
  );
});

test("the copy message names the reseller, the business and the link", () => {
  expect(shareMessage({ name: "Aling Rosa", orderLink: "https://county.example.ph/order/abc" }, "Nena's Longganisa")).toBe(
    "Hi Aling Rosa, you can order from Nena's Longganisa here: https://county.example.ph/order/abc",
  );
});

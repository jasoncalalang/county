import { describe, expect, test } from "vitest";
import { linkStatus } from "./resellers";

// 17:30 in Manila on 26 Feb 2027.
const EXPIRES = "2027-02-26T09:30:00Z";
const link = { linkExpiresAt: EXPIRES };

describe("linkStatus", () => {
  test("a link works up to the moment it expires", () => {
    expect(linkStatus(link, new Date("2027-02-26T09:29:59.999Z"))).toEqual({
      expired: false,
      label: "Link expires 26 Feb 2027",
    });
    expect(linkStatus(link, new Date("2026-11-28T00:00:00Z")).expired).toBe(false);
  });

  test("a link is expired at its expiry, as the API treats it, and after", () => {
    expect(linkStatus(link, new Date(EXPIRES))).toEqual({ expired: true, label: "Link expired 26 Feb 2027" });
    expect(linkStatus(link, new Date("2027-03-30T00:00:00Z"))).toEqual({
      expired: true,
      label: "Link expired 26 Feb 2027",
    });
  });

  test("the date is the day in Manila, not in UTC", () => {
    // 00:30 on 26 Feb in Manila is still 25 Feb in UTC.
    const early = { linkExpiresAt: "2027-02-25T16:30:00Z" };
    expect(linkStatus(early, new Date("2027-02-01T00:00:00Z")).label).toBe("Link expires 26 Feb 2027");
    // 23:30 on 26 Feb in Manila is 15:30 in UTC the same day.
    const late = { linkExpiresAt: "2027-02-26T15:30:00Z" };
    expect(linkStatus(late, new Date("2027-02-01T00:00:00Z")).label).toBe("Link expires 26 Feb 2027");
  });
});

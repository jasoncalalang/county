"use client";
import { useEffect, useState } from "react";
import ui from "../../ui.module.css";

/** "Updated 12 seconds ago" under a polled list; red after two missed polls. */
export function Freshness({ updatedAt, failures }: { updatedAt: number | null; failures: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  if (failures >= 2) return <p className={`${ui.small} ${ui.freshness} ${ui.dangerText}`}>Could not refresh, retrying</p>;
  if (updatedAt === null) return null;
  const seconds = Math.max(0, Math.round((now - updatedAt) / 1000));
  return (
    <p className={`${ui.small} ${ui.freshness}`}>
      Updated {seconds} {seconds === 1 ? "second" : "seconds"} ago
    </p>
  );
}

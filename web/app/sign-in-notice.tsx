"use client";
import { useSearchParams } from "next/navigation";
import ui from "./ui.module.css";

export function SignInNotice() {
  const params = useSearchParams();
  if (params.get("reason") === "expired") return <p className={ui.dangerText}>Please sign in again</p>;
  if (params.get("error")) return <p className={ui.dangerText}>Sign-in did not complete. Please try again.</p>;
  return null;
}

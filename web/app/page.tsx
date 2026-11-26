import { Suspense } from "react";
import { signIn } from "@/auth";
import ui from "./ui.module.css";
import { SignInNotice } from "./sign-in-notice";

// 6.1 Sign in. Signed-in visitors never see this page: proxy.ts sends them to /orders.
export default function SignIn() {
  async function continueWithGoogle() {
    "use server";
    await signIn("google", { redirectTo: "/orders" });
  }

  return (
    <main className={ui.centered}>
      <form action={continueWithGoogle} className={`${ui.card} ${ui.signIn}`}>
        <h1 className={ui.brand}>county</h1>
        <p>Sign in to your business</p>
        <Suspense>
          <SignInNotice />
        </Suspense>
        <button type="submit" className={`${ui.primary} ${ui.wide}`}>
          Continue with Google
        </button>
      </form>
    </main>
  );
}

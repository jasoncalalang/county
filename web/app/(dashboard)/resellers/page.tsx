"use client";
// 6.6 Resellers and their order links (functional spec 3.3).
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import type { ResellerView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { formatMobile, formatShortDate, manilaDate } from "@/lib/format";
import { shareMessage } from "@/lib/dashboard/share";
import { Dialog, DialogActions } from "../_components/dialog";
import { Placeholder, useShell } from "../_components/shell";
import ui from "../../ui.module.css";

const STATUS = { invited: "Invited", active: "Active", blocked: "Blocked" } as const;

type Modal =
  | { kind: "add" }
  | { kind: "reveal"; reseller: ResellerView }
  | { kind: "block"; reseller: ResellerView }
  | { kind: "reissue"; reseller: ResellerView }
  | null;

export default function ResellersPage() {
  const api = useApi();
  const { business, toast } = useShell();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const [resellers, setResellers] = useState<ResellerView[] | null>(null);
  const [modal, setModal] = useState<Modal>(params.get("add") ? { kind: "add" } : null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      setResellers(await api.get<ResellerView[]>("/resellers"));
    } catch {
      toast("Could not load resellers, try again", "error");
    }
  }, [api, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const close = () => {
    setModal(null);
    if (params.get("add")) router.replace(pathname);
  };

  const replace = (updated: ResellerView) =>
    setResellers((all) => all?.map((r) => (r.id === updated.id ? updated : r)) ?? all);

  async function action(reseller: ResellerView, name: "block" | "unblock" | "reissue-link") {
    setBusy(true);
    try {
      const updated = await api.post<ResellerView>(`/resellers/${reseller.id}/${name}`);
      replace(updated);
      if (name === "reissue-link") setModal({ kind: "reveal", reseller: updated });
      else {
        setModal(null);
        toast(name === "block" ? `${reseller.name} is blocked` : `${reseller.name} is unblocked`);
      }
    } catch {
      toast("Could not save, try again", "error");
    } finally {
      setBusy(false);
    }
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast(`${what} copied`);
    } catch {
      toast("Could not copy. Select the link and copy it.", "error");
    }
  }

  const rowActions = (r: ResellerView) => (
    <div className={ui.actions}>
      <button type="button" className={ui.textButton} onClick={() => setModal({ kind: "reveal", reseller: r })}>
        Link
      </button>
      {r.status === "blocked" ? (
        <button type="button" className={ui.textButton} disabled={busy} onClick={() => action(r, "unblock")}>
          Unblock
        </button>
      ) : (
        <button type="button" className={ui.dangerButton} onClick={() => setModal({ kind: "block", reseller: r })}>
          Block
        </button>
      )}
      <button type="button" className={ui.textButton} onClick={() => setModal({ kind: "reissue", reseller: r })}>
        Reissue
      </button>
    </div>
  );

  const badge = (r: ResellerView) => <span className={`${ui.badge} ${ui[r.status]}`}>{STATUS[r.status]}</span>;
  const lastOrder = (r: ResellerView) => (r.lastOrderAt ? formatShortDate(manilaDate(new Date(r.lastOrderAt))) : "No orders yet");

  return (
    <>
      <div className={ui.pageHeader}>
        <h1 className={ui.title}>Resellers</h1>
        <button type="button" className={ui.primary} onClick={() => setModal({ kind: "add" })}>
          Add reseller
        </button>
      </div>

      {resellers === null ? (
        <Placeholder />
      ) : resellers.length === 0 ? (
        <div className={ui.empty}>
          <h2 className={ui.heading}>No resellers yet</h2>
          <p>Add the people who order from you.</p>
          <button type="button" className={ui.primary} onClick={() => setModal({ kind: "add" })}>
            Add reseller
          </button>
        </div>
      ) : (
        <>
          <div className={ui.tableWrap}>
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Mobile</th>
                  <th scope="col">Status</th>
                  <th scope="col">Last order</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {resellers.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <strong>{r.name}</strong>
                    </td>
                    <td className={ui.num}>{formatMobile(r.mobile)}</td>
                    <td>{badge(r)}</td>
                    <td className={ui.small}>{lastOrder(r)}</td>
                    <td>{rowActions(r)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className={ui.cards}>
            {resellers.map((r) => (
              <li key={r.id}>
                <div className={ui.cardLine}>
                  <strong>{r.name}</strong>
                  {badge(r)}
                </div>
                <div className={ui.small}>
                  {formatMobile(r.mobile)} · {lastOrder(r)}
                </div>
                {rowActions(r)}
              </li>
            ))}
          </ul>
        </>
      )}

      <AddReseller
        open={modal?.kind === "add"}
        onClose={close}
        onAdded={(r) => {
          setResellers((all) => [...(all ?? []), r]);
          setModal({ kind: "reveal", reseller: r });
        }}
      />

      <Dialog
        open={modal?.kind === "reveal"}
        title={modal?.kind === "reveal" ? `Send this link to ${modal.reseller.name}` : ""}
        onClose={close}
      >
        {modal?.kind === "reveal" && (
          <>
            <p className={ui.small}>
              Paste it into Viber, Messenger or SMS. Anyone with the link can order as {modal.reseller.name}.
            </p>
            <div className={ui.linkBox}>{modal.reseller.orderLink}</div>
            <div className={ui.actions} style={{ justifyContent: "flex-start" }}>
              <button type="button" className={ui.secondary} onClick={() => copy(modal.reseller.orderLink, "Link")}>
                Copy link
              </button>
              <button
                type="button"
                className={ui.secondary}
                onClick={() => copy(shareMessage(modal.reseller, business.name), "Message")}
              >
                Copy message
              </button>
            </div>
            <DialogActions>
              <button type="button" className={ui.primary} onClick={close}>
                Done
              </button>
            </DialogActions>
          </>
        )}
      </Dialog>

      <Dialog
        open={modal?.kind === "block"}
        title={modal?.kind === "block" ? `Block ${modal.reseller.name}?` : ""}
        onClose={close}
      >
        {/* GUESS: the spec's copy says "Her link"; the reseller's pronoun is not known. */}
        <p>Their link stops working immediately.</p>
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={close}>
            Keep
          </button>
          <button
            type="button"
            className={`${ui.primary} ${ui.dangerPrimary}`}
            disabled={busy}
            onClick={() => modal?.kind === "block" && action(modal.reseller, "block")}
          >
            Block
          </button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={modal?.kind === "reissue"}
        title={modal?.kind === "reissue" ? `Reissue the link for ${modal.reseller.name}?` : ""}
        onClose={close}
      >
        <p>The old link stops working.</p>
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={close}>
            Keep
          </button>
          <button
            type="button"
            className={ui.primary}
            disabled={busy}
            onClick={() => modal?.kind === "reissue" && action(modal.reseller, "reissue-link")}
          >
            Reissue
          </button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function AddReseller({
  open,
  onClose,
  onAdded,
}: {
  open: boolean;
  onClose: () => void;
  onAdded: (reseller: ResellerView) => void;
}) {
  const api = useApi();
  const [errors, setErrors] = useState<{ name?: string; mobile?: string; form?: string }>({});
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const name = String(data.get("name") ?? "").trim();
    const mobile = String(data.get("mobile") ?? "").trim();
    const found: typeof errors = {};
    if (!name) found.name = "Enter a name.";
    else if (name.length > 80) found.name = "Use 80 characters or fewer.";
    if (!mobile) found.mobile = "Enter a Philippine mobile number";
    setErrors(found);
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      onAdded(await api.post<ResellerView>("/resellers", { name, mobile }));
      setErrors({});
    } catch (error) {
      // The API names the field at fault; a duplicate number is a 409 with no field.
      if (error instanceof ApiError && Object.keys(error.fields).length)
        setErrors({ name: error.fields.name, mobile: error.fields.mobile, form: error.fields.body });
      else if (error instanceof ApiError && error.status === 409)
        setErrors({ mobile: "Another reseller already has this number." });
      else setErrors({ form: "Could not save, try again" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} title="Add reseller" onClose={onClose}>
      <form onSubmit={submit} noValidate style={{ marginTop: 16 }}>
        <div className={ui.field}>
          <label htmlFor="reseller-name" className={ui.label}>
            Name
          </label>
          <input id="reseller-name" name="name" maxLength={80} autoComplete="off" aria-invalid={Boolean(errors.name)} />
          {errors.name && <span className={ui.fieldError}>{errors.name}</span>}
        </div>
        <div className={ui.field}>
          <label htmlFor="reseller-mobile" className={ui.label}>
            Mobile number
          </label>
          <input
            id="reseller-mobile"
            name="mobile"
            type="tel"
            inputMode="tel"
            autoComplete="off"
            aria-invalid={Boolean(errors.mobile)}
            aria-describedby="reseller-mobile-hint"
          />
          <span id="reseller-mobile-hint" className={errors.mobile ? ui.fieldError : ui.small}>
            {errors.mobile ?? "09XX XXX XXXX"}
          </span>
        </div>
        {errors.form && <p className={ui.fieldError}>{errors.form}</p>}
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={ui.primary} disabled={busy}>
            Add
          </button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

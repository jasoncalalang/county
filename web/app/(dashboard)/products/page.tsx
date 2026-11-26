"use client";
// 6.7 Products: the price list resellers see (functional spec 3.2).
import { useCallback, useEffect, useState } from "react";
import type { ProductInput, ProductView, Unit } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { formatMoney } from "@/lib/format";
import { Dialog, DialogActions } from "../_components/dialog";
import { Placeholder, useShell } from "../_components/shell";
import ui from "../../ui.module.css";

const UNITS: Unit[] = ["pack", "kg", "pc"];

/** "120", "120.5", "1,200.50" to a number of pesos with at most two decimals, else null. */
function readPrice(text: string): number | null {
  const clean = text.replace(/[₱,\s]/g, "");
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(clean)) return null;
  const value = Number(clean);
  return value > 0 ? value : null;
}

function sortProducts(products: ProductView[]) {
  return [...products].sort((a, b) => Number(b.active) - Number(a.active));
}

type Editing = { id: number; field: "name" | "price" | "unit" } | null;

export default function ProductsPage() {
  const api = useApi();
  const { toast } = useShell();
  const [products, setProducts] = useState<ProductView[] | null>(null);
  const [editing, setEditing] = useState<Editing>(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      setProducts(await api.get<ProductView[]>("/products"));
    } catch {
      toast("Could not load products, try again", "error");
    }
  }, [api, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  async function save(product: ProductView, change: Partial<ProductInput>): Promise<boolean> {
    const body: ProductInput = { name: product.name, unit: product.unit, price: product.price, active: product.active, ...change };
    try {
      const updated = await api.put<ProductView>(`/products/${product.id}`, body);
      setProducts((all) => all?.map((p) => (p.id === updated.id ? updated : p)) ?? all);
      return true;
    } catch (error) {
      // GUESS: the API does not say which products have orders, so the unit is editable until
      // the API refuses with 409 "Unit cannot change after ordering".
      if (error instanceof ApiError && error.status === 409 && change.unit) toast("This product is in use; its unit cannot change.", "error");
      else if (error instanceof ApiError && error.status === 409) toast("Another active product has that name.", "error");
      else if (error instanceof ApiError && Object.keys(error.fields).length)
        toast(Object.values(error.fields)[0], "error");
      else if (!(error instanceof ApiError && error.status === 401)) toast("Could not save, try again", "error");
      return false;
    }
  }

  const cell = (p: ProductView, field: "name" | "price", shown: React.ReactNode) =>
    editing?.id === p.id && editing.field === field ? (
      <InlineEdit
        label={field === "name" ? `Name of ${p.name}` : `Price of ${p.name}`}
        initial={field === "name" ? p.name : p.price.toFixed(2)}
        validate={(v) =>
          field === "name"
            ? v.trim() && v.trim().length <= 80
              ? null
              : "Use 1 to 80 characters."
            : readPrice(v) === null
              ? "Enter a price above ₱0.00."
              : null
        }
        onCancel={() => setEditing(null)}
        onSave={async (v) => {
          const ok = await save(p, field === "name" ? { name: v.trim() } : { price: readPrice(v) ?? p.price });
          if (ok) setEditing(null);
        }}
      />
    ) : (
      <button type="button" className={ui.cellButton} onClick={() => setEditing({ id: p.id, field })}>
        {shown}
      </button>
    );

  const unitCell = (p: ProductView) =>
    editing?.id === p.id && editing.field === "unit" ? (
      <select
        aria-label={`Unit of ${p.name}`}
        defaultValue={p.unit}
        autoFocus
        onBlur={() => setEditing(null)}
        onChange={async (e) => {
          await save(p, { unit: e.target.value as Unit });
          setEditing(null);
        }}
      >
        {UNITS.map((u) => (
          <option key={u}>{u}</option>
        ))}
      </select>
    ) : (
      <button type="button" className={ui.cellButton} onClick={() => setEditing({ id: p.id, field: "unit" })}>
        {p.unit}
      </button>
    );

  const toggle = (p: ProductView) => (
    <label className={ui.toggle}>
      <input type="checkbox" role="switch" checked={p.active} onChange={() => save(p, { active: !p.active })} />
      {p.active ? "Active" : "Inactive"}
    </label>
  );

  const sorted = products ? sortProducts(products) : null;

  return (
    <>
      <div className={ui.pageHeader}>
        <h1 className={ui.title}>Products</h1>
        <button type="button" className={ui.primary} onClick={() => setAdding(true)}>
          Add product
        </button>
      </div>

      {sorted === null ? (
        <Placeholder />
      ) : sorted.length === 0 ? (
        <div className={ui.empty}>
          <h2 className={ui.heading}>No products yet</h2>
          <p>Add what you sell and its price.</p>
          <button type="button" className={ui.primary} onClick={() => setAdding(true)}>
            Add product
          </button>
        </div>
      ) : (
        <>
          <div className={ui.tableWrap}>
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">Product</th>
                  <th scope="col">Unit</th>
                  <th scope="col" className={ui.right}>
                    Price
                  </th>
                  <th scope="col">Active</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((p) => (
                  <tr key={p.id} className={p.active ? undefined : ui.inactiveRow}>
                    <td>{cell(p, "name", p.name)}</td>
                    <td>{unitCell(p)}</td>
                    <td className={ui.right}>{cell(p, "price", formatMoney(p.price))}</td>
                    <td>{toggle(p)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className={ui.cards}>
            {sorted.map((p) => (
              <li key={p.id}>
                <div className={ui.cardLine}>
                  <strong className={p.active ? undefined : ui.inactiveName}>{cell(p, "name", p.name)}</strong>
                  <strong className={ui.num}>{cell(p, "price", formatMoney(p.price))}</strong>
                </div>
                <div className={ui.small}>per {unitCell(p)}</div>
                <div className={ui.cardLine}>{toggle(p)}</div>
              </li>
            ))}
          </ul>
        </>
      )}

      <AddProduct
        open={adding}
        onClose={() => setAdding(false)}
        onAdded={(p) => {
          setProducts((all) => [...(all ?? []), p]);
          setAdding(false);
          toast(`${p.name} added`);
        }}
      />
    </>
  );
}

function InlineEdit({
  label,
  initial,
  validate,
  onSave,
  onCancel,
}: {
  label: string;
  initial: string;
  validate: (value: string) => string | null;
  onSave: (value: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function commit() {
    const problem = validate(value);
    setError(problem);
    if (problem) return;
    setBusy(true);
    await onSave(value);
    setBusy(false);
  }

  return (
    <span>
      <span className={ui.inlineEdit}>
        <input
          aria-label={label}
          value={value}
          autoFocus
          aria-invalid={Boolean(error)}
          inputMode={label.startsWith("Price") ? "decimal" : undefined}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") commit();
            if (e.key === "Escape") onCancel();
          }}
        />
        <button type="button" className={ui.textButton} disabled={busy} onClick={commit}>
          Save
        </button>
        <button type="button" className={ui.textButton} onClick={onCancel}>
          Cancel
        </button>
      </span>
      {error && <span className={ui.fieldError}>{error}</span>}
    </span>
  );
}

function AddProduct({
  open,
  onClose,
  onAdded,
}: {
  open: boolean;
  onClose: () => void;
  onAdded: (product: ProductView) => void;
}) {
  const api = useApi();
  const [errors, setErrors] = useState<{ name?: string; price?: string; form?: string }>({});
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const name = String(data.get("name") ?? "").trim();
    const unit = String(data.get("unit")) as Unit;
    const price = readPrice(String(data.get("price") ?? ""));
    const found: typeof errors = {};
    if (!name || name.length > 80) found.name = "Use 1 to 80 characters.";
    if (price === null) found.price = "Enter a price above ₱0.00.";
    setErrors(found);
    if (Object.keys(found).length || price === null) return;
    setBusy(true);
    try {
      onAdded(await api.post<ProductView>("/products", { name, unit, price, active: true }));
      form.reset();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) setErrors({ name: "An active product already has this name." });
      else if (error instanceof ApiError && Object.keys(error.fields).length)
        setErrors({ name: error.fields.name ?? error.fields.unit, price: error.fields.price, form: error.fields.body });
      else setErrors({ form: "Could not save, try again" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} title="Add product" onClose={onClose}>
      <form onSubmit={submit} noValidate style={{ marginTop: 16 }}>
        <div className={ui.field}>
          <label htmlFor="product-name" className={ui.label}>
            Name
          </label>
          <input id="product-name" name="name" maxLength={80} autoComplete="off" aria-invalid={Boolean(errors.name)} />
          {errors.name && <span className={ui.fieldError}>{errors.name}</span>}
        </div>
        <div className={ui.field}>
          <label htmlFor="product-unit" className={ui.label}>
            Unit
          </label>
          <select id="product-unit" name="unit" defaultValue="pack">
            {UNITS.map((u) => (
              <option key={u}>{u}</option>
            ))}
          </select>
        </div>
        <div className={ui.field}>
          <label htmlFor="product-price" className={ui.label}>
            Price
          </label>
          <span className={ui.prefix}>
            ₱
            <input id="product-price" name="price" inputMode="decimal" autoComplete="off" aria-invalid={Boolean(errors.price)} />
          </span>
          {errors.price && <span className={ui.fieldError}>{errors.price}</span>}
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

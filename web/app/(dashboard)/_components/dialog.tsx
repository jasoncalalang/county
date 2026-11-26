"use client";
import { useEffect, useId, useRef } from "react";
import ui from "../../ui.module.css";

/** A centred modal on the native <dialog>: it traps focus and closes on Escape. */
export function Dialog({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className={ui.dialog}
      aria-labelledby={titleId}
      // Escape: let the owner's state close it, so a programmatic close never reports back.
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      {open && (
        <>
          <h2 id={titleId} className={ui.heading}>
            {title}
          </h2>
          {children}
        </>
      )}
    </dialog>
  );
}

export function DialogActions({ children }: { children: React.ReactNode }) {
  return <div className={ui.dialogActions}>{children}</div>;
}

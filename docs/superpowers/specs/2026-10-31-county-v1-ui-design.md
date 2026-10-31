# county v1: interface design

Date: 2026-10-31
Status: proposed; gives form to the
[features and functionality](https://github.com/jasoncalalang/county-architecture/blob/main/design/2026-10-31-v1-functional-spec.md)
of the
[v1 design](https://github.com/jasoncalalang/county-architecture/blob/main/design/2026-10-16-v1-design.md)

## 1. Purpose

Two interfaces share one product. The **dashboard** is used by a wholesaler
on a laptop in the morning and on a phone at the market. The **order page**
is used by a reseller on an inexpensive Android phone inside the browser of
Viber or Messenger, often on a slow connection. This document fixes what
each screen shows, how it behaves, and the visual rules both share, so that
the implementation plan can build screens without design decisions left
open.

Mockups of every screen are kept as artboards in a design canvas outside
this repository; this document is the record.

## 2. Principles

1. **Legible before pretty.** System fonts, one accent colour, black text on
   light surfaces. Status is always a word, never only a colour.
2. **The order page is a form.** It works with JavaScript off. Everything a
   reseller does is a link or a submit button.
3. **One action per row, one click.** Confirm, deliver, cancel and mark paid
   sit on the order row. Only cancel asks "are you sure".
4. **The server decides, the screen shows.** Delivery dates, totals and
   allowed actions come from the API. The dashboard never computes a rule.
5. **Every list has an empty state that says what to do next.**
6. **Numbers line up.** Money and quantities use tabular figures and are
   right-aligned in every table.

## 3. Visual foundations

Light theme only in v1. The in-app browsers that resellers use do not
reliably honour dark mode, and one theme keeps the dashboard small.

### Colour

| Token | Value | Use |
|---|---|---|
| `ink` | `#1C1917` | Text, headings |
| `ink-muted` | `#57534E` | Secondary text, timestamps, hints |
| `surface` | `#FFFFFF` | Cards, table rows, inputs |
| `surface-page` | `#F5F2EE` | Page background |
| `border` | `#D6D0C8` | Hairlines, input borders |
| `accent` | `#176B3A` | Primary buttons, links, focus ring, active nav |
| `accent-strong` | `#125530` | Primary button hover and press |
| `danger` | `#B42318` | Cancel and block as text buttons; field errors |
| `status-placed` | `#7C4A03` on `#FEF3C7` | Placed badge |
| `status-confirmed` | `#1E3A8A` on `#DBEAFE` | Confirmed badge |
| `status-delivered` | `#14532D` on `#DCFCE7` | Delivered badge |
| `status-cancelled` | `#57534E` on `#E7E5E4` | Cancelled badge |

Every text and ground pair above is at least 4.5:1. Badges always carry
their word, so a reader who cannot tell amber from blue is not lost.

### Type

System font stack, no web fonts anywhere:
`system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", sans-serif`.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `title` | 24 / 32 (28 / 36 from 900px) | 600 | Page title |
| `heading` | 20 / 28 | 600 | Section and card titles, order number on detail |
| `body` | 16 / 24 | 400 | Everything else; the minimum on the order page |
| `body-strong` | 16 / 24 | 600 | Reseller names, totals |
| `small` | 14 / 20 | 400 | Table secondary cells, hints, "Updated 12 s ago" |
| `label` | 13 / 16 | 600, uppercase, 0.04em | Table headers, form labels, badges |

Money and quantities use `font-variant-numeric: tabular-nums`.

### Space, shape, size

| Token | Value |
|---|---|
| spacing scale | 4, 8, 12, 16, 24, 32, 48 px |
| `radius-control` | 6 px for inputs, buttons, badges |
| `radius-card` | 10 px for cards and dialogs |
| `tap-target` | 44 px minimum height for every button, link row and input |
| gutters | 16 px on phones, 24 px from 900 px |
| dashboard content width | 1100 px maximum, centred |
| order page width | 480 px maximum, centred |
| focus ring | 2 px solid `accent`, 2 px offset |

No shadows except on the open dialog. No icons in v1; the few glyphs are
typographic: `‹ ›` on the date picker, `×` on dismiss, `←` on back links.

## 4. Dashboard components

| Component | Shape | Notes |
|---|---|---|
| App bar | Business name on the left, navigation in the middle, Sign out on the right. 56 px tall. | Below 900 px the navigation becomes a horizontally scrolling row under the bar. |
| Navigation | Orders, Production run, Resellers, Products, Settings | Active item in `accent` with a 2 px underline. |
| Page header | `title`, then the page's control row | Control row holds the date picker or the Add button. |
| Date picker | `‹` Wed, 5 Nov 2026 `›` plus a native date input | Arrows step to the previous or next configured delivery day. |
| Status chips | All 3 · Placed 1 · Confirmed 2 · Delivered 0 · Cancelled 0 | Single select. Counts come from the loaded list. |
| Summary line | "3 orders · ₱5,280.00 · 1 needs confirmation" | `small`, `ink-muted`; the warning part in `status-placed` text. |
| Table | Header row in `label`; rows 56 px; numbers right-aligned | Becomes a card list below 900 px (section 9). |
| Status badge | Word in a pill, colours above | Paid shows as a second badge, "Paid" in delivered colours or "Unpaid" in cancelled colours. |
| Buttons | Primary: white on `accent`. Secondary: `ink` on `surface` with `border`. Text: `accent` or `danger`, no border. | All 44 px tall, 16 px side padding, `body-strong`. |
| Row actions | Right-aligned; the main action is a secondary button, cancel is a danger text button | Placed: Confirm, Cancel. Confirmed: Deliver, Cancel. Delivered unpaid: Mark paid. |
| Dialog | Centred card, 400 px, title, one line of body, buttons right | Deliver: "Was order #3 paid?" with No and Yes. Cancel: "Cancel order #3?" with Keep and Cancel order. |
| Toast | Bottom centre, `ink` on `surface`, 4 s | "Order #3 confirmed", "This order is now confirmed". |
| Form field | Label above, input 44 px, hint or error below in `small` | Error text in `danger`, input border in `danger`. |
| Link reveal | Monospace link in a bordered box with Copy link and Copy message | Shown after adding a reseller and from the row's Link action. |
| Toggle | Active or Inactive as a switch with the word beside it | Products and nothing else. |
| Empty state | Centred `heading`, one `body` line, one primary button | Text per screen in section 6. |
| Freshness line | "Updated 12 seconds ago" under a polled list | Turns to "Could not refresh, retrying" in `danger` after two failed polls. |

## 5. Order page components

The order page has its own stylesheet of under 8 KB inlined into the HTML.
It shares the colour and type tokens and nothing else.

| Component | Shape |
|---|---|
| Header | Business name in `heading`; "Ordering as Aling Rosa" in `small` |
| Delivery banner | Bordered box: "Delivery on Thu, 6 Nov" in `body-strong`; the cut-off line in `small` |
| Product row | Name and unit on the left, price below in `small`; quantity input 64 px wide, 44 px tall, right. Rows separated by hairlines. |
| Quantity input | `<input type="number" inputmode="numeric" min="0" max="9999">`, value 0 by default |
| Note | `<textarea>` with a 500 character limit, label "Note for your supplier (optional)" |
| Submit | Full-width primary button, "Place order", sticky to the bottom of the viewport when the script loads, static otherwise |
| Running total | A line above the button, "Total ₱2,800.00", filled by the optional script; absent without it |
| Recent orders | List rows: "#4 · Thu, 6 Nov · ₱2,160.00" with a badge; a Cancel text button on placed orders inside its own `<form>` |
| Error block | Above the form, `danger` text, one line per error, each a link to the row |
| Confirmation | Card with "Order #6 placed" in `title`, delivery date, line table, total, note, and a "Back to price list" link |
| Inactive | Centred `heading` and one line of `body`; nothing else on the page |

## 6. Dashboard screens

### 6.1 Sign in `/`

Centred card: "county", "Sign in to your business", one primary button
**Continue with Google**. Nothing else. Below 480 px the card fills the
width.

### 6.2 Set up your business `/setup`

Centred form, 480 px: Business name; Cut-off time as a native time input
(4:00 PM); Delivery days as seven checkboxes in a row, Mon to Sat checked;
a `small` line "Timezone Asia/Manila · Currency PHP"; primary **Create
business**. Validation per the functional spec, inline.

### 6.3 Orders `/orders`

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Nena's Longganisa    Orders  Production run  Resellers  Products  Settings  │
├─────────────────────────────────────────────────────────────────────────────┤
│ Orders                                                                      │
│ Delivery date   ‹  Wed, 5 Nov 2026  ›   [date]                              │
│ (All 3) (Placed 1) (Confirmed 2) (Delivered 0) (Cancelled 0)                │
│ 3 orders · ₱5,280.00 · 1 needs confirmation                                 │
│ ┌────┬────────────┬──────────┬────────────────────────────┬──────────┬───────────┬──────┬──────────────────┐
│ │ #  │ RESELLER   │ PLACED   │ ITEMS                      │    TOTAL │ STATUS    │ PAID │                  │
│ ├────┼────────────┼──────────┼────────────────────────────┼──────────┼───────────┼──────┼──────────────────┤
│ │ #3 │ Mang Ben   │ 9:12 AM  │ 20 pack Recado             │ ₱2,200.00│ Placed    │      │ Confirm   Cancel │
│ │ #4 │ Aling Rosa │ 8:51 AM  │ 10 pack Hamonado, 3 kg Toc…│ ₱2,160.00│ Confirmed │      │ Deliver   Cancel │
│ │ #2 │ Tita Lorna │ Tue 3:40 │ 5 pack Hamonado, 1 kg Toci…│   ₱920.00│ Confirmed │      │ Deliver   Cancel │
│ └────┴────────────┴──────────┴────────────────────────────┴──────────┴───────────┴──────┴──────────────────┘
│ Updated 12 seconds ago                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

- Rows are ordered placed first, then confirmed, then delivered, then
  cancelled; within a status, newest first. The row's number links to the
  detail.
- Placed shows the time if today, else the weekday and time.
- Items truncate to one line with an ellipsis; the detail has the full list.
- Clicking **Confirm** replaces the buttons with a spinner for the request,
  then the badge and buttons update in place and a toast says "Order #3
  confirmed". No page reload.
- A 409 updates the row to the status in the response and toasts "This
  order is now confirmed".
- The list refetches every 30 seconds and after any action; a fetch never
  clears the table, it swaps rows when the response arrives.
- Empty state for a date: "No orders for Wed, 5 Nov", "Orders placed before
  4:00 PM today will show here.", no button.
- Empty state for a new business: "No orders yet", "Add a reseller and send
  them their link.", button **Add reseller**.

### 6.4 Order detail `/orders/{id}`

Header: `←` Orders link, then "Order #4" with its badges beside it. Two
columns from 900 px, stacked below:

- Left: lines table (Product, Qty, Unit price, Line total), total row in
  `body-strong`, then the note in a quoted block.
- Right: card with Reseller (name, mobile), Delivery date, Placed at, and a
  History list: "Placed Wed 8:51 AM", "Confirmed Wed 9:02 AM", one line per
  recorded timestamp.
- Actions in the header right, the same buttons as the row.

### 6.5 Production run `/production-run`

Header with the date picker and a secondary **Print** button. Then:

- A notice when placed orders exist for the date, in `status-placed`
  colours: "1 order is not yet confirmed and is not included." linking to
  Orders filtered to Placed.
- Table: Product, Unit, Quantity (right), Orders (right). Rows in price
  list order. Products with no confirmed quantity are omitted.
- Footer line: "2 confirmed orders · ₱3,080.00".
- Empty state: "Nothing to make for Wed, 5 Nov", "Confirm orders to see
  them here.", button **Go to orders**.
- Print stylesheet: hides the app bar, controls and notice link; adds
  "Nena's Longganisa · Production run · Wed, 5 Nov 2026" as the page
  header; table at 12 pt; fits A4 portrait.

### 6.6 Resellers `/resellers`

Header with primary **Add reseller**. Table: Name, Mobile, Status, Last
order, actions (Link, Block or Unblock, Reissue). Status as a badge:
Invited in cancelled colours, Active in delivered colours, Blocked in
`danger` text on `#FEE4E2`.

- **Add reseller** opens a dialog: Name, Mobile number (hint "09XX XXX
  XXXX"), primary **Add**. On success the dialog becomes the link reveal
  with "Send this link to Aling Rosa", the link box, **Copy link**, **Copy
  message**, and **Done**.
- **Link** on a row opens the same reveal.
- **Block** asks "Block Aling Rosa? Her link stops working immediately."
  with Keep and Block. Unblock does not ask.
- **Reissue** asks "Reissue the link for Aling Rosa? The old link stops
  working." with Keep and Reissue, then shows the reveal with the new link.
- Empty state: "No resellers yet", "Add the people who order from you.",
  button **Add reseller**.

### 6.7 Products `/products`

Header with primary **Add product**. Table: Product, Unit, Price (right),
Active toggle. Active rows first.

- Clicking a name or price makes that cell an input with Save and Cancel
  text buttons; Enter saves, Escape cancels. Unit is a select only while
  the product has no orders; otherwise it is text with the hint "In use".
- **Add product** is a dialog: Name, Unit select, Price (prefix ₱), primary
  **Add**.
- Deactivating does not ask. The row moves below the active rows and the
  name turns `ink-muted`.
- Empty state: "No products yet", "Add what you sell and its price.",
  button **Add product**.

### 6.8 Settings `/settings`

One form, 480 px wide, same fields as set-up, with the preview line "An
order placed now would be delivered on Thu, 6 Nov." updating as the fields
change by asking the API, and a primary **Save**. A `small` success line
"Saved" appears beside the button for 4 s.

## 7. Order page screens

### 7.1 Price list `/order/{token}`

```
┌──────────────────────────────┐
│ Nena's Longganisa            │
│ Ordering as Aling Rosa       │
│ ┌──────────────────────────┐ │
│ │ Delivery on Thu, 6 Nov   │ │
│ │ Orders after 4:00 PM are │ │
│ │ delivered Fri, 7 Nov.    │ │
│ └──────────────────────────┘ │
│ Longganisa Hamonado   ┌────┐ │
│ pack · ₱120.00        │ 0  │ │
│ ──────────────────────└────┘ │
│ Longganisa Recado     ┌────┐ │
│ pack · ₱110.00        │ 0  │ │
│ ──────────────────────└────┘ │
│ Tocino                ┌────┐ │
│ kg · ₱320.00          │ 0  │ │
│ ──────────────────────└────┘ │
│ Note for your supplier       │
│ ┌──────────────────────────┐ │
│ │                          │ │
│ └──────────────────────────┘ │
│ Total ₱0.00        (script)  │
│ ┌──────────────────────────┐ │
│ │       Place order        │ │
│ └──────────────────────────┘ │
│ Your recent orders           │
│ #4 · Wed, 5 Nov · ₱2,160.00  │
│    Confirmed                 │
│ #1 · Tue, 4 Nov · ₱2,800.00  │
│    Delivered                 │
└──────────────────────────────┘
```

- One column at every width; 480 px maximum, 16 px gutters.
- The quantity input is the only control per row; tapping anywhere on the
  row's text focuses it (the row is a `<label>`).
- The page is a single `<form method="post">`. The hidden idempotency key
  is inside it.
- With the optional script: the running total updates on input; the submit
  button becomes sticky at the bottom; a double tap on submit is ignored
  while the request is in flight. Without it, the browser's own behaviour
  applies and the server's idempotency handles a repeat.
- Recent orders are a list under the form; a placed order's row has a
  **Cancel** text button, which is its own one-button `<form>` posting to
  the cancel route. The browser's `confirm()` asks "Cancel order #5?" when
  the script runs; without it, the cancel route shows a one-button
  confirmation page.
- Page budget: HTML plus inline CSS under 50 KB, no images, no fonts, one
  optional script under 2 KB.

### 7.2 Validation re-render

The same page, with an error block above the form: "Add at least one
product." or "Tocino is no longer available." Each error links to its row;
the row's input shows a `danger` border. Entered quantities and the note
are kept.

### 7.3 Confirmation `/order/{token}/orders/{id}`

Card: "Order #6 placed" in `title`; "Delivery on Thu, 6 Nov"; line table
(name, qty × price, line total) with a total row; the note under "Your
note"; `←` **Back to price list**. Opening this URL again shows the same
page with the current status badge; a cancelled order says "Order #6
cancelled".

### 7.4 Inactive link

"This link is no longer active." in `heading` and "Please contact your
supplier." in `body`, centred, HTTP 404. No business name, no other link.

## 8. States and feedback

| State | Dashboard | Order page |
|---|---|---|
| Loading | Table shows three grey placeholder rows for the first load only; later polls swap rows silently | Server-rendered, no loading state |
| Empty | Per-screen text in section 6 | "No products available yet. Please contact your supplier." in place of the form |
| Field error | `danger` text under the field, border `danger`, focus moves to the first error | Error block above the form, rows marked |
| Request error | Toast "Could not save, try again" in `danger`; the control re-enables | Full page with the error block |
| Stale action (409) | Row updates to the real status, toast "This order is now <status>" | Cancel of a confirmed order shows "Order #5 is confirmed. Contact your supplier to change it." |
| Signed out (401) | Silent re-sign-in; if that fails, the sign-in screen with "Please sign in again" | Not applicable |
| Poll failure | Freshness line turns `danger` after two misses; the table keeps the last data | Not applicable |

## 9. Responsive rules

- Breakpoint at 900 px. Above: tables and the two-column detail. Below:
  every table becomes a list of cards.
- An order card: first line `#3 · Mang Ben` left and `₱2,200.00` right in
  `body-strong`; second line items in `small`; third line badges left and
  actions right. 44 px buttons, full width when two actions do not fit.
- Reseller and product cards follow the same shape: name and the key
  number on the first line, secondary text second, actions third.
- The date picker keeps its arrows at every width; chips scroll
  horizontally when they do not fit.
- Dialogs fill the width below 480 px with 16 px margins.
- The order page is single-column at every width and never shows a table
  except on the confirmation, where the line table has three columns that
  wrap the product name.

## 10. Accessibility

- Every control is reachable by keyboard; focus ring as in section 3.
- Every input has a `<label>`; tables have `<th scope="col">`; badges are
  text, so screen readers read the status.
- Toasts use `role="status"`; the dialog traps focus and closes on Escape.
- The order page uses `inputmode="numeric"` so the numeric keypad opens,
  and `autocomplete="off"` on quantities.
- Nothing is conveyed by colour alone; nothing animates except the toast
  fade, which `prefers-reduced-motion` turns off.
- Text is never smaller than 13 px, and 16 px on the order page.

## 11. Implementation notes

- One `tokens.css` holds the values in section 3 as custom properties and
  is the only stylesheet both interfaces import.
- The dashboard is plain CSS with CSS modules; no component library in v1.
  If one is adopted later, it maps onto these tokens rather than bringing
  its own.
- The order page's CSS is a separate file inlined at build time; it must
  not import the dashboard's styles, so the budget in 7.1 stays
  enforceable with a size check in `npm run build`.
- Dates and money are formatted in web with `Intl` in `en-PH` and the
  tenant's timezone, from values the API returns; web never derives a date.
- The Playwright demo loop (functional spec section 10) is the visual
  regression test: it takes a screenshot of each screen in section 6 and 7
  at 390 px and 1280 px.

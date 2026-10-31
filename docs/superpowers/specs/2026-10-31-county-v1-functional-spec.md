# county v1: features and functionality

Date: 2026-10-31
Status: proposed; details the features of the
[v1 design](https://github.com/jasoncalalang/county-architecture/blob/main/design/2026-10-16-v1-design.md)
and runs on the
[v1 Kubernetes deployment](https://github.com/jasoncalalang/county-architecture/blob/main/design/2026-10-31-v1-kubernetes-deployment.md)

## 1. Who uses it

| Role | Who | How they get in | What they can do |
|---|---|---|---|
| Wholesaler | The owner of the business. One per tenant in v1. | Google sign-in on the dashboard. | Everything in section 3. |
| Reseller | A market stall, sari-sari store or carinderia that buys from the wholesaler. | Opens their order link. No account, no password. | Section 4, only for themselves. |
| Anyone else | | | Nothing. Every other URL is a sign-in page or a 404. |

Terms used throughout:

| Term | Meaning |
|---|---|
| Business, tenant | One wholesaler. All data belongs to exactly one. |
| Price list | The active products with their current prices. What a reseller sees. |
| Order link | `https://county.example.ph/order/{token}`, unique per reseller. The only thing a reseller needs. |
| Cut-off | A time of day in the business's timezone. Orders placed at or after it ship a day later. |
| Delivery days | The weekdays the business delivers on. |
| Delivery date | The date an order will be delivered. Computed by the server, never chosen. |
| Production run | For one delivery date, the quantity of each product across all confirmed orders. What the kitchen makes. |
| Paid | A flag on an order. v1 records that money arrived, not how. |

## 2. Feature list

Everything in v1, in one table. Sections 3 and 4 give each line its acceptance criteria.

| Area | Feature | Role |
|---|---|---|
| Account | Sign in with Google; create the business on first sign-in | Wholesaler |
| Settings | Business name, cut-off time, delivery days | Wholesaler |
| Price list | Add a product; edit name, unit, price; deactivate and reactivate | Wholesaler |
| Resellers | Add a reseller with name and mobile number; get their order link and a ready-to-paste message | Wholesaler |
| Resellers | Block and unblock a reseller; reissue a link | Wholesaler |
| Ordering | Open the link, see the price list and the delivery date | Reseller |
| Ordering | Place an order with quantities and a note; see a confirmation with the order number and total | Reseller |
| Ordering | See recent orders; cancel one that is still placed | Reseller |
| Orders | See orders for a delivery date, filtered by status, refreshed every 30 seconds | Wholesaler |
| Orders | Confirm, deliver (recording paid or not), cancel, mark paid later | Wholesaler |
| Orders | Open one order: lines, note, status history | Wholesaler |
| Production run | Per-product totals of confirmed orders for a delivery date; printable | Wholesaler |

Not in v1, by decision: SMS sending, stock, more than one user per business,
payments beyond the flag, price tiers, minimum order quantities, product
images, editing an order after placement (ADR 0012), deleting anything.
Section 12 lists what comes after.

## 3. Wholesaler features

Acceptance criteria are written so that each becomes a test (principle 5).

### 3.1 Sign in and set up the business

- Signing in with a Google account that has never signed in creates the
  user and sends them to **Set up your business**: business name, cut-off
  (default 4:00 PM), delivery days (default Monday to Saturday). Timezone is
  shown as Asia/Manila and currency as PHP; neither is editable in v1.
- Submitting creates the tenant and lands on **Orders**.
- Signing in again with the same Google account lands on **Orders** directly.
- A user without a business who opens any dashboard URL is sent to set-up.
- A business name is 1 to 80 characters. At least one delivery day is
  required.

### 3.2 Manage the price list

- **Products** lists every product, active first, with name, unit, price and
  an active toggle. Inline edit for name and price; unit is editable only
  while the product has no orders.
- Adding a product needs a name (1 to 80 characters, unique within the
  business among active products), a unit (pack, kg or pc) and a price
  greater than zero in pesos and centavos.
- Deactivating a product hides it from every order link immediately. Orders
  that already contain it are untouched. Reactivating puts it back.
- Changing a price affects orders placed after the change only; every order
  line keeps the price at the time it was placed.
- There is no delete.

### 3.3 Manage resellers and order links

- **Resellers** lists name, mobile number, status (invited, active,
  blocked), last order date, and the actions copy link, copy message, block
  or unblock, reissue link.
- Adding a reseller needs a name (1 to 80 characters) and a Philippine
  mobile number, unique within the business. The reseller starts as
  *invited* and the page shows their link with a **Copy link** button and a
  **Copy message** button that copies
  `Hi <name>, you can order from <business> here: <link>` for pasting into
  Viber, Messenger or SMS.
- A reseller becomes *active* when their first order is placed.
- Blocking makes the link show "no longer active" within one request. The
  reseller's existing orders are unchanged and still workable by the
  wholesaler. Unblocking makes the same link work again.
- Reissuing a link generates a new token; the old link is dead from that
  moment and the new link is shown for copying. Use it when a link has
  leaked. It works on invited, active and blocked resellers.
- There is no delete.

### 3.4 Work the day's orders

- **Orders** shows orders for one *delivery date*. The date picker defaults
  to the earliest delivery date that has an order not yet delivered or
  cancelled, else the next delivery date. Status chips filter by placed,
  confirmed, delivered, cancelled, or all.
- A summary line shows counts per status and the peso total of orders that
  are not cancelled.
- Each row: order number, reseller, placed time, a one-line summary of lines
  ("10 pack Hamonado, 5 kg Tocino"), total, status, paid, actions.
- Actions by status:
  - placed: **Confirm**, **Cancel**.
  - confirmed: **Deliver** (asks "Paid?" yes or no), **Cancel**.
  - delivered and unpaid: **Mark paid**.
  - cancelled: none.
- Every action takes effect in one click plus, for cancel, one confirmation.
- The list refreshes every 30 seconds and after every action. An action on
  an order whose status changed elsewhere shows "This order is now
  *confirmed*" and refreshes the row; nothing is applied twice.
- **Order detail** shows the reseller, the lines with unit prices and line
  totals, the note, paid, and a history of status changes with timestamps.

### 3.5 Production run

- **Production run** shows, for one delivery date, each product with unit,
  total quantity, and the number of orders it appears in. Only confirmed
  orders count.
- If placed orders exist for that date, a notice says "n orders are not yet
  confirmed and are not included", linking to the orders page filtered to
  placed.
- The page prints cleanly on A4 with the business name and date in the
  header. That printout is what goes to the kitchen.

### 3.6 Settings

- Business name, cut-off time and delivery days as in 3.1. Changes apply to
  orders placed after saving; existing orders keep their delivery date.
- The page shows a preview: "An order placed now would be delivered on
  <date>."

## 4. Reseller features

Everything here works with JavaScript disabled, inside the Viber, Messenger
and Chrome browsers on a low-end Android phone (ADR 0001, ADR 0003).

### 4.1 Open the link

- `GET /order/{token}` shows the business name, "Ordering as <reseller
  name>", the delivery date ("Delivery on Tue, 4 Nov"), a line explaining
  the cut-off ("Orders placed after 4:00 PM are delivered Wed, 5 Nov"),
  the price list as a form, and recent orders.
- The page is the same whether it is the first visit or the hundredth. No
  cookie is set; nothing is remembered on the phone.
- An unknown, reissued or blocked token shows "This link is no longer
  active. Please contact your supplier." with no other content and HTTP 404.

### 4.2 Place an order

- The price list is one row per active product: name, unit, price, and a
  quantity field (numeric keyboard, 0 to 9999, default 0). Below: an
  optional note (up to 500 characters) and a **Place order** button.
- Submitting with every quantity at 0 re-renders with "Add at least one
  product". A product that was deactivated between render and submit
  re-renders with "<name> is no longer available" on that row and keeps the
  other quantities.
- A successful submit shows the **confirmation page**: "Order #23 placed",
  the delivery date, each line with its total, the order total, the note,
  and a link back to the order page. The order is now visible to the
  wholesaler as *placed*.
- Pressing back and submitting the same form again returns the same
  confirmation for the same order number. No second order is created.
- Two orders for the same delivery date are allowed and stay separate.

### 4.3 See recent orders and cancel

- Below the form, the last 10 orders, newest first: order number, delivery
  date, total, status, and for *placed* orders a **Cancel** button.
- Cancel asks once ("Cancel order #23?") and then shows the order as
  cancelled. A reseller cannot cancel a confirmed order; the page says
  "Order #23 is confirmed. Contact your supplier to change it."

### 4.4 What a reseller cannot do

Edit an order, choose a delivery date, see prices other than the current
list, see other resellers, or reach any page without the token.

## 5. Rules with worked examples

### 5.1 Delivery date

Computed in the business's timezone at the moment the page is rendered and
again at submit; the submit value wins and is shown on the confirmation.

With the defaults, cut-off 4:00 PM, delivery days Monday to Saturday:

| Placed at | Earliest allowed | Delivery date |
|---|---|---|
| Mon 10:00 | Tue | Tue |
| Mon 15:59 | Tue | Tue |
| Mon 16:00 | Wed | Wed |
| Sat 10:00 | Sun, not a delivery day | Mon |
| Sat 16:30 | Mon | Mon |

With delivery days Monday, Wednesday, Friday:

| Placed at | Earliest allowed | Delivery date |
|---|---|---|
| Mon 10:00 | Tue | Wed |
| Mon 16:00 | Wed | Wed |
| Wed 15:59 | Thu | Fri |
| Fri 16:00 | Sun | Mon |

The cut-off is tested at one minute before, at, and one minute after, per
the v1 design.

### 5.2 Order numbers

Per business, starting at 1, increasing by one, never reused. A cancelled
order keeps its number. Allocated in the same transaction as the order, so
there are no gaps. Shown everywhere as `#23`, which is what a wholesaler and
a reseller say to each other on the phone.

### 5.3 Money and quantities

- Prices and totals are pesos with centavos, stored as `numeric(12,2)`.
  Shown as `₱1,234.50`.
- Quantity is a whole number from 1 to 9999 of the product's unit. "Half a
  kilo" is a separate product if the business sells it. No decimals in v1.
- Line total is quantity times the unit price at placement. Order total is
  the sum of line totals. No tax, discount or delivery fee in v1.

### 5.4 Mobile numbers

Philippine mobile numbers only. Accepted on input: `09171234567`,
`9171234567`, `+639171234567`, `639171234567`, with or without spaces and
dashes. Stored as `+639171234567`. Shown as `0917 123 4567`. Unique per
business. Anything else is a 422 with "Enter a Philippine mobile number".

### 5.5 Tokens and idempotency

- A token is 128 random bits, base64url, 22 characters, generated by the
  server. It appears only inside the link.
- Each render of the order form carries a fresh UUID in a hidden field. The
  order stores it; a second submit with the same key returns the first order
  (HTTP 200, same confirmation page). Keys are unique per business forever.

### 5.6 Status machine

| From | To | Who | Shown as |
|---|---|---|---|
| placed | confirmed | wholesaler | Confirmed |
| placed | cancelled | reseller or wholesaler | Cancelled |
| confirmed | delivered | wholesaler, with paid yes or no | Delivered, Paid or Unpaid |
| confirmed | cancelled | wholesaler | Cancelled |
| delivered, unpaid | delivered, paid | wholesaler | Paid |

Every other transition is a 409 naming the current status. Each transition
records a timestamp (`placed_at`, `confirmed_at`, `delivered_at`,
`cancelled_at`, `paid_at`), which the order detail shows as history.

### 5.7 What changes affect what

| Change | Existing orders | New orders |
|---|---|---|
| Price edited | unchanged | new price |
| Product deactivated | unchanged | rejected on that line |
| Reseller blocked | unchanged, still workable | link shows inactive |
| Link reissued | unchanged | only the new link works |
| Settings changed | keep their delivery date | computed with new settings |

Nothing is deleted in v1. Deactivate, block and cancel are the only ways
to retire something, so history always adds up.

## 6. Screens

Dashboard (web, client-rendered, behind Google sign-in):

| Path | Purpose | Main actions |
|---|---|---|
| `/` | Redirects to `/orders`, or to sign-in | |
| `/auth/*` | Auth.js: sign in, callback, sign out | |
| `/setup` | Create the business | Save |
| `/orders` | Orders for a delivery date | Date, status filter, confirm, deliver, cancel, mark paid |
| `/orders/{id}` | One order | Same actions, history |
| `/production-run` | Per-product totals for a delivery date | Date, print |
| `/resellers` | Resellers and links | Add, copy link, copy message, block, unblock, reissue |
| `/products` | Price list | Add, edit, activate, deactivate |
| `/settings` | Business settings | Save |

Reseller (web, server-rendered, no sign-in):

| Path | Purpose |
|---|---|
| `GET /order/{token}` | Price list form, delivery date, recent orders |
| `POST /order/{token}` | Submits the form; on success redirects to the confirmation |
| `GET /order/{token}/orders/{id}` | Confirmation page for one order |
| `POST /order/{token}/orders/{id}/cancel` | Cancels a placed order; redirects back to the order page |

The reseller pages are HTML forms posting to web routes, which call the API
server-side with the token (ADR 0010). A small script, when it loads, shows
a running total under the form; nothing depends on it.

## 7. Copy

English in v1. Pesos as `₱`, dates as `Tue, 4 Nov`, times as `4:00 PM`.

| Situation | Text |
|---|---|
| Order page header | Delivery on Tue, 4 Nov. Orders placed after 4:00 PM are delivered Wed, 5 Nov. |
| Inactive link | This link is no longer active. Please contact your supplier. |
| Empty order | Add at least one product. |
| Deactivated product | <name> is no longer available. |
| Confirmation | Order #23 placed. Delivery on Tue, 4 Nov. Total ₱2,800.00. |
| Reseller cancels a confirmed order | Order #23 is confirmed. Contact your supplier to change it. |
| Stale dashboard action | This order is now <status>. |
| Copy message | Hi <name>, you can order from <business> here: <link> |
| Unconfirmed warning on production run | <n> orders are not yet confirmed and are not included. |

## 8. Permissions

| Action | Wholesaler | Reseller with their token | Anyone |
|---|---|---|---|
| Create business, settings, products, resellers | own business | no | no |
| See and work orders, production run | own business | no | no |
| See price list and delivery date | yes | own | no |
| Place an order | no | own | no |
| Cancel an order | own business, placed or confirmed | own, placed only | no |
| See another business's anything | 404 | 404 | 404 |

The wholesaler does not place orders on a reseller's behalf in v1. If a
reseller phones an order in, the wholesaler opens that reseller's link
themselves; the order is then recorded exactly like any other.

## 9. Non-functional

- **Reseller page weight**: at most 50 KB transferred for the order page
  including CSS, no web fonts, no images. Server render under 200 ms at the
  API. Usable on a 2 GB Android phone over 3G inside Viber's browser.
- **Dashboard**: desktop and phone widths; the owner will confirm orders
  from a phone at the market.
- **Accessibility**: tap targets at least 44 px, every input labelled,
  visible focus, text at least 16 px, no colour as the only signal of status.
- **Language**: English. Tagalog is a later feature.
- **Data**: nothing is deleted or exported in v1. Timestamps are UTC in the
  database and shown in the business's timezone.
- **Limits that hold the UI design**: a price list under 50 products, under
  200 resellers, under 200 orders per delivery date. No search or paging in
  v1; above these, add them.

## 10. Demo script

This is the Playwright acceptance test from the v1 design, in the order it
is run before every demo.

1. Sign in with the demo Google account. Set up **Nena's Longganisa**,
   cut-off 4:00 PM, Monday to Saturday.
2. Add products: Longganisa Hamonado, pack, ₱120; Longganisa Recado, pack,
   ₱110; Tocino, kg, ₱320.
3. Add reseller **Aling Rosa**, 0917 123 4567. Copy the link.
4. In a fresh browser context with no cookies, open the link. The page shows
   Nena's Longganisa, Ordering as Aling Rosa, the delivery date, three
   products, no recent orders.
5. Order 10 Hamonado and 5 Tocino with the note "before 7am". The
   confirmation shows order #1, total ₱2,800.00.
6. Go back and submit again. The same confirmation for #1 appears; the
   dashboard shows one order.
7. On the dashboard, Aling Rosa is now active; order #1 is placed for the
   expected delivery date. Confirm it.
8. Production run for that date: Hamonado 10 pack in 1 order, Tocino 5 kg
   in 1 order. No unconfirmed warning.
9. Deliver #1 with paid no. It shows Delivered, Unpaid. Mark paid. It shows
   Paid.
10. Block Aling Rosa. Her link shows "This link is no longer active".
    Unblock. It works again.

## 11. Amendments to the v1 design

Detailing the features surfaced these changes to the v1 design. They are
proposed here and folded into the design document when accepted.

- `GET /orders?date=` filters by requested delivery date, not placement
  date; the dashboard is organised by delivery date throughout.
- `POST /resellers/{id}/reissue-link` replaces the token and returns the new
  link, per the "block and reissue" mitigation in ADR 0003.
- `POST /orders/{id}/mark-paid` sets paid on a delivered order, so a
  delivery on credit can be settled later.
- `GET /order/{token}` also returns the business name and the reseller name.
- Order gains `confirmed_at`, `delivered_at`, `cancelled_at` and `paid_at`.
- Tenant gains `next_order_number`.
- Orders are immutable after placement (ADR 0012); no edit endpoint is
  added.

## 12. Beyond v1

| Feature | Why it waits |
|---|---|
| Send the link and order updates by SMS or Viber through a Philippine gateway | Needs a gateway account and spend; manual paste is enough for a demo. |
| Stock and lots: what is on hand, what each run consumed | The next slice after v1 by the design. |
| Several users per business with roles (staff confirm and deliver, owner sets prices) | One owner is enough to demo. |
| Payments: GCash or Maya reference, partial payments, receivables per reseller | The paid flag demos the idea. |
| Price tiers, per-reseller price lists, minimum order quantities | Every reseller pays the same in the reference customer. |
| Wholesaler edits and short-delivery discrepancy reports | Cancel and reorder covers the demo (ADR 0012). |
| Reports and CSV export: sales by reseller, product, period | The production run is the only report a demo needs. |
| Tagalog and Bisaya interface | English copy is understood by the reference users. |
| Product images, search, paging | Lists are short and the reseller page must stay light. |
| POS, BIR receipts, branches, accounting integrations | Out of scope by the design. |

# Plan: W1 fractional quantities

Date: 2026-11-28. Work item: `docs/agent-playbook.md` W1. Decision: ADR 0017 (quantity up to
three decimals, line total rounded half up to centavos, order total is the sum of rounded
lines). Also ADR 0012 (captured lines never change). Builds on W3 (PR #14). Next free
migration: `V7`.

## 1. Scope and non-goals

In scope:

- Order and quote lines take a decimal quantity: greater than 0, at most 9999, up to three
  decimal places (ADR 0017). Quotes are included because they share `Inputs.Line` and an
  accepted quote copies its lines into an order.
- `Money.line(price, quantity)` rounds half up to centavos; the order or quote total is the
  sum of the rounded lines.
- Decimals are for products sold by weight: a `kg` product accepts them; `pack` and `pc`
  stay whole numbers. (Asked of Jason on 2026-11-28; this plan builds the recommended answer.
  If he chooses "every unit", the rule in `domain/Unit` and its tests are removed.)
- Quantities leave the API without trailing zeros (`10`, `1.5`), so existing clients and
  tests that read whole quantities keep working.
- Web: the reseller order page and the new-quote form accept decimals for `kg` products and
  validate the same rule; screens show quantities as the API sends them.
- Docs: `api/README.md`, functional spec §5.3 copy, playbook board.

Non-goals (YAGNI):

- No `measurement_unit` table or `product.measurement_unit_id`. The playbook lists them, but
  nothing would read them: there is no endpoint to manage units and ADR 0017 does not ask for
  them. `product.unit` stays the unit. Flagged for Jason in the PR.
- No unit conversions, no new units, no per-product step or minimum.
- No change to `order_line.price` or `total` scale. Quantity stays `NOT NULL`.
- No stock (W7) or variants (W8).

## 2. Threat model

Assets: the money on an order (line and order totals); the production run quantities the
business prepares from; service availability on the one unauthenticated write.

Actors: owner; reseller holding a link; anonymous caller; owner of another tenant.

Entry points: `POST /api/order/{token}/orders` (reseller), `POST /api/quotes` (owner),
`POST /api/order/{token}/quotes/{id}/accept` (copies quantities), and the web forms in front
of the first two.

| # | Abuse case | Control | Test |
|---|---|---|---|
| B1 | Order a tiny quantity whose line rounds to ₱0.00 and get goods for free | `Money.line` refuses a line total below ₱0.01; reported on `lines[i].quantity` as 422 | T3, T9 |
| B2 | Smuggle precision: four or more decimals, to bias rounding or overflow the column | `@Digits(integer = 4, fraction = 3)`; 422 on `lines[i].quantity` | T6 |
| B3 | Zero, negative, non-numeric or above 9999 | `@DecimalMin("0.001")`, `@DecimalMax("9999")`, typed input; 422 | T6 |
| B4 | Huge exponent (`1e400`, `1e-400`) to burn CPU or cause a 500 | Bounds are checked on the parsed decimal without expanding it; 422, never 500 | T7 |
| B5 | Total overflow with a large fractional quantity and price | Existing `numeric(12,2)` ceiling in `Money.line` and on the running total | T2, T10 |
| B6 | Fractional `pack` or `pc` (half a pack) | `domain/Unit` allows fractions for `kg` only; 422 on `lines[i].quantity` | T8 |
| B7 | Re-rounding at quote acceptance changes what was quoted | Acceptance copies the quote line's quantity, price and total unchanged | T12 |
| B8 | Tenant B sees tenant A's fractional lines in its production run | Production run is tenant-scoped (unchanged) | T13 |
| B9 | Markup in a typed quantity on the reseller page | Web parser accepts only digits and one decimal point; the page renders through the escaping `html` template | W1, W4 |
| B10 | The web is bypassed and the API called directly | Every rule above is enforced in the API; web validation only spares a round trip | T6–T9 |

## 3. Design

### Migration `V7__fractional_quantities.sql`

For `order_line` and `quote_line`: drop the `quantity BETWEEN 1 AND 9999` check (named
`order_line_quantity_check`, `quote_line_quantity_check`; verify in a scratch schema), change
`quantity` to `NUMERIC(7,3)`, add `CHECK (quantity > 0 AND quantity <= 9999)`. Existing
integers convert exactly. Expand-first: the previous release still writes integers. Works on
Postgres 16 and 17. (`NUMERIC(7,3)` rather than the playbook's `(12,3)`: four integer digits
is the rule, so the column enforces it.)

### API

- `domain/Money.line(BigDecimal price, BigDecimal quantity)`: validates the quantity (not
  null, > 0, ≤ 9999, scale ≤ 3), multiplies, rounds `HALF_UP` to scale 2, refuses a result
  below 0.01 or above the ceiling. Class comment updated: it no longer promises exactness.
- `domain/Unit` (new enum `PACK`, `KG`, `PC`): `of(String stored)` and
  `allows(BigDecimal quantity)` (fractions for `KG` only).
- `domain/Quantity` (new, final, static): `wire(BigDecimal)` strips trailing zeros and never
  yields exponent notation (`10.000` → `10`, `1.500` → `1.5`).
- `Inputs.Line.quantity`: `@NotNull @DecimalMin("0.001") @DecimalMax("9999")
  @Digits(integer = 4, fraction = 3) BigDecimal`.
- `persistence/Pricing` (new service): takes over `Orders.price`, `PricedLine`, `Priced` and
  the tier price lookup it uses, unchanged in locking (share-locks products by ascending id,
  after reseller and business). It applies the unit rule and maps a `Money` refusal for a
  quantity to a field error on `lines[i].quantity`. `Orders` and `Quotes` inject it. `Orders`
  shrinks; nothing is added to `Orders`, `Owners` or `OwnerResource`.
- `OrderLine.quantity`, `QuoteLine.quantity`: `BigDecimal`.
- `Views.OrderLine`, `OrderItem`, `QuoteLine`, `ProductionLine`: `quantity` becomes
  `BigDecimal` (OpenAPI `number`), passed through `Quantity.wire`. The production run sums
  decimals.
- No new lock target and no new endpoint.
- OpenAPI: `quantity` changes from `integer` to `number` in `Line` and the four views, with
  the new bounds. Nothing else.

### Web

- `web/lib/quantity.ts` (new, pure): `parseQuantity(typed, unit)` returns the number or a
  message. Accepts `^\d{1,4}(\.\d{1,3})?$`, greater than 0, at most 9999; whole numbers only
  unless the unit is `kg`. Empty and `0` mean "not ordered", as today.
- `web/lib/order-page/handlers.ts` and `web/lib/dashboard/quotes.ts` use it in place of their
  own whole-number regexes; they need each product's unit (the handler already loads the
  catalog on errors; load it before validating, or validate decimals generically and let the
  API's 422 name the unit rule: builder picks the smaller change and says which).
- `web/lib/order-page/render.ts`: a `kg` product's input is `inputmode="decimal"
  step="0.001"`; others stay `inputmode="numeric" step="1"`. The page still works with
  JavaScript off. The live total script (`script.ts`) must show the same half-up line totals.
- `web/e2e/fake-api.mjs`: decimal quantities, half-up line totals, the kg-only rule.

### Docs

`api/README.md` ("quantity whole units" sentence and the money paragraph), the §5.3 copy in
`docs/superpowers/specs/2026-10-31-county-v1-functional-spec.md` (ADR 0017 supersedes "No
decimals in v1"), playbook board W1 → done with the `measurement_unit` deferral noted.

## 4. Test list

Each fails first on an assertion. For signature changes, the `test:` commit adds the new
`Money.line(BigDecimal, BigDecimal)` overload throwing `UnsupportedOperationException`, and
widens the wire types only as far as compiling needs.

| # | Behaviour | Class | Fails first because |
|---|---|---|---|
| T1 | `120.00 × 1.5 = 180.00`; `120.00 × 0.333 = 39.96`; `99.99 × 0.333 = 33.30` (half up); `0.01 × 0.5 = 0.01` | `domain/DomainRulesTest` (Money section) | overload throws |
| T2 | Quantity null, 0, negative, above 9999 or with four decimals is refused; a total above the ceiling is refused | same | overload throws a different exception |
| T3 | A line total below ₱0.01 (`1.00 × 0.004`) is refused | same | as T2 |
| T4 | `Unit`: `kg` allows `1.5`; `pack` and `pc` allow `2` and `2.000` but not `1.5`; unknown stored unit is refused | `domain/UnitTest` (new) | stub |
| T5 | `Quantity.wire`: `10.000` → `10`, `1.500` → `1.5`, `1000` stays `1000` (no `1E+3`) | `domain/QuantityTest` (new) | stub |
| T6 | Placing with quantity `0`, `-1`, `9999.001`, `10000`, `1.0005`, `"abc"` is 422 keyed `lines[0].quantity` | `api/FractionalQuantityApiTest` (new) | fractional forms return other statuses today |
| T7 | Quantity `1e400` and `1e-400` are 422, not 500 | same | assert status |
| T8 | `1.5` of a `pack` product is 422 on `lines[0].quantity`; `1.5` of a `kg` product is 201 | same | 422/400 today for both |
| T9 | `0.004 kg` at ₱1.00 is 422 on `lines[0].quantity` | same | not rejected for that reason |
| T10 | An order of `1.5 kg` at ₱120 returns line total `180.00` and quantity `1.5`; a second line `0.333 kg` at ₱99.99 totals `33.30`; order total is the sum; it reads back the same through get, the owner list `items`, confirm and the production run (sum `1.5`, and `3` after a second order of `1.5`) | same | 422 today |
| T11 | Whole quantities still read as whole numbers on the wire (`10`, not `10.000`) | same | passes today; keep as a guard and say so |
| T12 | A quote with `1.5 kg` is created, sent and accepted; the order's line quantity, price and total equal the quote's | `api/QuoteApiTest` | 422 today |
| T13 | Tenant B's production run shows none of tenant A's fractional lines | `api/IsolationApiTest` | A's order cannot be placed today |
| T14 | Two concurrent fractional orders still number sequentially | `api/ConcurrentOrderingTest` | 422 today |
| T15 | Schema: `quantity` is `numeric(7,3)` on both tables, and `0` or `10000` is rejected by the database | `api/SchemaNamingTest` or a new schema test | integer today |
| W1 | `parseQuantity`: `1.5`/`kg` ok; `1.5`/`pack` message; `1.2345`, `-1`, `1e3`, `1,5`, `<b>1</b>`, `10000` messages; `""` and `0` mean none | `web/lib/quantity.test.ts` (new) | stub |
| W2 | Reseller handler sends `{quantity: 1.5}` for a kg product and re-renders with a message for a bad value | `web/lib/order-page/handlers.test.ts` | whole-number regex |
| W3 | `quoteInput` accepts `1.5` for a kg product | `web/lib/dashboard/quotes.test.ts` | whole-number regex |
| W4 | e2e with JavaScript disabled: order `1.5 kg`, see quantity `1.5 kg` and the half-up line total on the confirmation | `web/e2e/smoke.spec.ts` (or wherever the reseller flow lives) | rejected today |

## 5. Commit sequence

1. `docs: plan W1 fractional quantities` (this file, alone).
2. Cycle 1, domain: `test:` T1–T5 → `feat:` `Money.line` overload, `Unit`, `Quantity` →
   `refactor:` or reason. The old `int` overload stays until cycle 2 removes its last caller.
3. Cycle 2, API: `test:` T6–T15 → `feat:` V7, entities, `Inputs.Line`, views, pricing,
   OpenAPI → `refactor:` extract `Pricing` from `Orders`, delete the `int` overload.
4. Cycle 3, web: `test:` W1–W4 → `feat:` → `refactor:` or reason.
5. `docs:` README, spec §5.3 copy, playbook board.

Existing tests that assert whole quantities must keep passing unchanged (T11 guards the wire
format). If one cannot, stop and report rather than editing it.

## 6. Open questions

- Asked of Jason, building the recommended answer meanwhile: decimals for `kg` only, or for
  every unit.
- For Jason in review: the `measurement_unit` table from the playbook is left out (YAGNI).

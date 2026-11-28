# Plan: W2 expiring reseller links

Date: 2026-11-28. Work item: `docs/agent-playbook.md` W2, the part that remains. Decision:
ADR 0018 (links expire 90 days after issue; reissue gives a new token and a new 90 days; an
expired link behaves exactly like a blocked one). ADR 0003 (the token is the reseller's only
credential). Rate limiting stays at the Cloudflare edge (Jason, 2026-11-26; `CLAUDE.md`).
Next free migration: `V9`.

## 1. Scope and non-goals

In scope:

- Every reseller link has an expiry, 90 days after the token was issued. Creating a reseller
  and reissuing a link both issue a token with a fresh 90 days.
- An expired link is refused on every `/api/order/{token}/...` endpoint with the same 404
  and body as a blocked or unknown link.
- Existing links are backfilled to expire 90 days after the migration runs (ADR 0018).
- The owner sees when each link expires (`ResellerView.linkExpiresAt`) and the resellers page
  shows it, marks an expired link and offers reissue (ADR 0018's web follow-up).
- Playbook W2 is rewritten to match the decisions: the in-API rate limiter, its settings and
  tests, and the per-token daily cap are removed from the item; rate limiting is an edge rule.

Non-goals (YAGNI):

- No rate limiter, captcha or per-token order cap in the API.
- No configurable lifetime: 90 days is a constant from the ADR.
- No automatic renewal on use (ADR 0018 rejected it), no expiry warnings or notifications
  to resellers, no "expiring soon" emails.
- No change to block and unblock behaviour. Reissuing a blocked reseller's link leaves the
  reseller blocked.
- No token hashing at rest (ADR 0003 stores the token so the owner can copy the link).

## 2. Threat model

Assets: the reseller link (a bearer credential that can read prices and order history, place
and cancel orders, accept quotes and file reports); the knowledge of whether a token ever
existed.

Actors: owner; reseller holding a current link; holder of an old or leaked link; anonymous
caller; owner of another tenant.

Entry points: every `/api/order/{token}/...` endpoint; `POST /api/resellers`,
`PUT /api/resellers/{id}`, `POST /api/resellers/{id}/reissue-link|block|unblock`,
`GET /api/resellers`.

| # | Abuse case | Control | Test |
|---|---|---|---|
| D1 | A link forwarded or leaked long ago is used after 90 days | Lookup requires `token_expires_at > now`; 404 | T2 |
| D2 | A new reseller endpoint forgets the expiry check | One lookup (`ResellerLinks.resolve`) used by every reseller service; a test walks every `/api/order/{token}` operation in the committed OpenAPI contract and expects 404 for an expired link | T3 |
| D3 | Telling expired from blocked from unknown tokens (an oracle for probing) | Same status, same body, one query path | T4 |
| D4 | The old token still works after reissue | Reissue replaces the token; old one is unknown; 404 | T6 |
| D5 | A link in steady use never expires | Using a link never changes its expiry | T5 |
| D6 | The request sets or extends the expiry | No input carries it; extra fields are ignored | T8 |
| D7 | Owner of tenant B reissues or reads tenant A's reseller link | Tenant from the verified subject; 404; list is tenant-scoped | T9 |
| D8 | Anonymous reissue | `@Authenticated`; 401 | T9 |
| D9 | An idempotent replay through an expired link returns the old order | The link is resolved before the idempotency lookup; 404 | T3 (place with a used key) |
| D10 | Unblocking revives an expired link | Unblock does not touch token or expiry | T7 |
| D11 | Existing links stay immortal after the upgrade | Migration backfills 90 days and the column is `NOT NULL` | T1 |
| D12 | A weak or repeated token on reissue | Unchanged 128-bit `SecureRandom` token; test checks format and that it changes | T6 |
| D13 | Token or expiry details leak in logs or error bodies | Nothing new is logged; the 404 body is the generic one | T4 |

## 3. Design

### Migration `V9__reseller_link_expiry.sql`

`ALTER TABLE reseller ADD COLUMN token_expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() +
interval '90 days');` One statement backfills existing rows and keeps inserts by the previous
release working (expand-first). Postgres 16 and 17.

### API

- `domain/ResellerLink` (new): `LIFETIME = Duration.ofDays(90)`, `expiry(Instant issuedAt)`. Pure.
  Whether a link is still active is not a domain method: the one query in `Reseller.byToken`
  (`tokenExpiresAt > now`) decides it, so a second rule would be dead code.
- `persistence/ResellerLinks` (new service, injects `Clock`): `resolve(token, lock)` returns
  the reseller for a well-formed, known, unblocked, unexpired token or throws the same
  `Problem(404, "Not found")` as today; `issue(reseller)` sets a new token and expiry. The
  token generator moves here from `Owners`. The expiry is part of the query, so there is one
  code path for every refusal.
- `Orders.reseller(token, lock)` (static) is removed. `Orders`, `OrderActions`, `Quotes` and
  `Discrepancies` inject `ResellerLinks` and call `resolve`. Lock order is unchanged: the
  reseller row is still the first lock.
- `persistence/Resellers` (new service) and `api/OwnerResellerResource` (new): the reseller
  list, create, update, block, unblock and reissue move out of `Owners` and `OwnerResource`,
  with the same paths and behaviour, as one method per action (`block`, `unblock`,
  `reissueLink`) instead of `resellerAction(String)`. `assignResellerTier` stays with price
  tiers. `Owners` and `OwnerResource` shrink.
- `Views.Reseller` gains `linkExpiresAt` (date-time). Nothing else in the contract changes;
  the moved operations keep their paths, tags and schemas.

### Web

- `web/lib/api-types.ts`: `ResellerView.linkExpiresAt`.
- `web/lib/dashboard/resellers.ts` (new or existing helper file, pure): `linkStatus(reseller,
  now)` returns `{expired: boolean, label}` such as "Link expires 26 Feb 2027" or "Link
  expired 26 Feb 2027". Takes `now` as an argument.
- Resellers page: shows the label per reseller; an expired link is marked and its share
  action is replaced by "Reissue link"; after a reissue the reveal dialog shows the new expiry.
- Reseller page: no change. An expired link gets the API's 404 and the existing "This link is
  no longer active" page.
- `web/e2e/fake-api.mjs`: `linkExpiresAt` on resellers, refreshed by reissue.

### Docs

`api/README.md` (link lifetime, reissue, the single lookup); playbook W2 rewritten and marked
done; playbook §2 "no rate limiting" gap reworded to point at the edge rule.

## 4. Test list

API tests set `token_expires_at` in the past through `TestSql` where an expired link is needed.

| # | Behaviour | Class | Fails first because |
|---|---|---|---|
| T0 | `ResellerLink`: the lifetime is 90 days and the expiry is issue time plus 90 days | `domain/ResellerLinkTest` (new) | stub |
| T1 | Schema: `token_expires_at` is `NOT NULL`; a reseller inserted without it gets about 90 days | `api/ResellerLinkSchemaTest` (new) | no column |
| T2 | A new reseller's `linkExpiresAt` is 90 days ahead (within a minute); the link works; once `token_expires_at` is in the past the catalog is 404 | `api/ResellerLinkApiTest` (new) | field absent; expired link still 200 |
| T3 | Every operation under `/api/order/{token}` in `api/contract/openapi.yaml` answers 404 for an expired link (ids `1`, a valid JSON body where one is needed, including a placement that replays a used idempotency key) | same | expired link is served |
| T4 | Expired, blocked, unknown and malformed tokens get byte-identical 404 bodies and content types | same | expired link is served |
| T5 | Placing an order does not change `token_expires_at` | same | guard; passes when written, say so |
| T6 | Reissue: new token (22 URL-safe characters, different), `linkExpiresAt` 90 days ahead even when the old link had expired, old token 404, new token works | same | expiry not renewed |
| T7 | Unblock does not renew or revive an expired link; reissue on a blocked reseller leaves it blocked | same | expired link is served |
| T8 | `linkExpiresAt` or `tokenExpiresAt` in a create or update body is ignored | same | field absent |
| T9 | Tenant B's owner gets 404 reissuing tenant A's reseller and never sees A's resellers; reissue without a token is 401 | `api/IsolationApiTest` | guard for the moved endpoints; passes when written, say so |
| W1 | `linkStatus`: before, at and after the expiry; label text | `web/lib/dashboard/resellers.test.ts` | stub |
| W2 | e2e: the resellers page shows the expiry; reissue shows a later expiry in the reveal dialog | `web/e2e/demo.spec.ts` | not rendered |

## 5. Commit sequence

1. `docs: plan W2 expiring reseller links` (this file, alone).
2. Cycle 1, rule: `test:` T0 → `feat:` → `refactor:` or reason.
3. Cycle 2, expiry on the link: `test:` T1–T5 → `feat:` V9, entity field, expiry in the token
   lookup, `linkExpiresAt` in the view, OpenAPI → `refactor:` extract `ResellerLinks` with the
   injected clock, remove `Orders.reseller`, switch the four services.
4. Cycle 3, reissue: `test:` T6–T9 → `feat:` → `refactor:` move the reseller endpoints to
   `Resellers` and `OwnerResellerResource`, one method per action.
5. Cycle 4, web: `test:` W1, W2 → `feat:` → `refactor:` or reason.
6. `docs:` README and playbook W2.

## 6. Open questions

None. For Jason to note in review: owners must reissue every 90 days (ADR 0018), and the
dashboard is the only reminder.

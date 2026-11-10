# county

Ordering between wholesalers and their resellers. Design spec in
`docs/superpowers/specs/`; architecture notes and decisions live in the
[county-architecture](https://github.com/jasoncalalang/county-architecture) repository.

## Layout

| Path | What |
|---|---|
| `api/` | Quarkus REST API (Java 21, Gradle). See [`api/README.md`](api/README.md). |
| `web/` | Next.js dashboard and reseller order page. See [`web/README.md`](web/README.md). |
| `docs/` | Specs. |

The API tests need a Postgres and no Docker; `api/README.md` says how to
provide one.

## License

Copyright 2026 Jason Calalang.

Licensed under the GNU Affero General Public License v3.0. See `LICENSE`.

## License

Copyright 2026 Jason Calalang.

The documents in this repository are licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). See `LICENSE`.

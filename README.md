# county

Ordering between wholesalers and their resellers. Design spec in
`docs/superpowers/specs/`; architecture notes and decisions live in the
[county-architecture](https://github.com/jasoncalalang/county-architecture) repository.

## License

Copyright 2026 Jason Calalang.

Licensed under the GNU Affero General Public License v3.0. See `LICENSE`.

## License

Copyright 2026 Jason Calalang.

The documents in this repository are licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). See `LICENSE`.

## Backend development

The initial Quarkus backend is in [`api/`](api/). Start with
[`api/README.md`](api/README.md) for local PostgreSQL, authentication and test
commands. Backend CI verifies changes without deploying anything. The architecture
repository is canonical; its newer functional/UI amendments are not yet copied into
this repository's original design snapshots.

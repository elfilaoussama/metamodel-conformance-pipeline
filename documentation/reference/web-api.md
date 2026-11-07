# Web API

The browser UI is rendered by Spring MVC/Thymeleaf at `/`. Navigation uses
ordinary links/query parameters; HTML forms POST to `/ui/runs`, `/ui/imports`,
`/ui/runs/{id}/replay` and `/ui/runs/{id}/cancel`, then redirect with HTTP 303.
Forms use `application/x-www-form-urlencoded` and a hidden session-bound `_csrf`.
Unsupported/duplicate form fields reject. Both HTML and JSON interfaces invoke
the same Java job/artifact services. Templates/CSS are inside the Java JAR;
the JSON contract below remains available for programmatic use and status polls.

Repository discovery HTML at `/repositories` accepts `mode=BUILDER|RAW`, `q`,
`language`, `owner`, `ownerType=user|org`, `topic`, `license`, `scope`,
`starsMin/Max`, `forksMin/Max`, `sizeMin/Max` (KiB), `createdFrom/To`,
`pushedFrom/To` (ISO dates), `archived`, `forkScope`, `sort`, `order` and `page`.
`search=preview` validates/renders without requesting GitHub; `search=1` searches.
`GET /repositories/preview` returns `{valid,query}` or `{valid:false,field,message}`
using the same compiler, with no external request or receipt creation. Raw mode
ignores guided criteria. Legacy `q`-only links open in raw mode. A successful UI
search stores an optional `criteria` map in the existing discovery receipt;
older receipts without that map remain readable. Pagination preserves criterion
values, including reserved characters such as `C++`.

Same-origin JSON under `/api/v1`. Establish a local session with `GET /session`;
send its cookie on subsequent requests and `X-CSRF-Token` on JSON POSTs. No CORS
or arbitrary command/file-access endpoint. POST bodies have a 16 KiB bound.

| Endpoint | Implemented behavior |
|---|---|
| `GET /capabilities` | Engine hash, bundled-profile restriction, actual execution/import modes, input root and recovery block |
| `GET /runs`, `/runs/{id}` | Latest 100 metadata records / one durable job |
| `POST /runs/preflight` | Source-only Java availability; body contains `source` |
| `POST /runs` | `{label, source, idempotencyKey}`; retain source and queue Java Analyze; 202 |
| `POST /imports` | `{label, result, source?, idempotencyKey}`; copy ordinary result files and optional source, then inspect; 202 |
| `GET /repositories/search?q=...&sort=stars&order=desc&page=1` | Public GitHub query; 20/page, first 1,000 results; timestamped retained search ID, candidates, incomplete-result and rate-limit metadata |
| `GET /discoveries/{id}` | Download the retained query, timestamp, candidate page and search metadata |
| `POST /repositories` | `{label?,url,revision,subdirectory?,analyze,idempotencyKey,discoveryId?}`; queue public GitHub acquisition; optional default Java analysis; 202 |
| `POST /repositories/{id}/analyze` | `{idempotencyKey}`; new Java analysis from a completed source-only import, after complete inventory/hash checks; no second fetch |
| `POST /runs/{id}/replay` | `{idempotencyKey}`; new linked real replay job; 202 |
| `POST /runs/{id}/cancel` | `{}`; asynchronous owned cancellation |
| `GET /runs/{id}/events?after=0` | Up to 100 ordered durable `{sequence, run}` events; poll with the last sequence. SSE is not implemented yet. |
| `GET /runs/{id}/overview` | Capsule decisions, dynamic rule metadata, counts, evidence and all linked replay attempts |
| `GET /runs/{id}/entities?kind=classifiers&offset=0&limit=50` | `{items,total,offset,limit}`; kinds: classifiers, members, methodBodies, implementationBindings, units, diagnostics; page limit 100 |
| `GET /runs/{id}/entities/{key}` | Exact contextual technical-key lookup, no short-name matching |
| `GET /runs/{id}/source?path=...` | Inventory-constrained retained UTF-8 source; hash mismatch rejects |
| `GET /runs/{id}/artifacts/{name}` | Allowlisted original download; final artifacts require completed artifact-bearing operation |

Submission keys contain 8–100 alphanumeric/hyphen characters. A repeated
identical key/specification returns the existing job; changed specifications
conflict. New submissions do not overwrite old attempts. Input directories must
resolve inside the startup-approved trusted root. No upload/archive extraction
or source/build-provider profile parameters exist in this initial contract.

Job records contain `id,label,operation,state,created,updated,engineSha256,
message,exitCode,replayOf,idempotencyKey`. Operations: ANALYZE, IMPORT, REPLAY,
GITHUB_IMPORT, GITHUB_ANALYZE. GITHUB_IMPORT completion means pinned source is
retained, without scientific decisions; `/overview` and formal replay reject it.
`POST /ui/repositories/{id}/analyze` is the equivalent protected HTML form for
analyzing a fully retained Java source tree later.
States: QUEUED, RUNNING, CANCELLING, COMPLETED, FAILED, TIMED_OUT, CANCELLED,
INTERRUPTED. Scientific decisions remain unmodified engine enums in the
capsule; a completed job is not an assertion that every condition conforms.

Replay completion/exit zero indicates VALID formal replay. Its failure or
timeout remains an operational record and log, without copied/fabricated
decisions. The original run remains immutable. Inspection is explicitly
`INTEGRITY_CHECKED_NOT_REPLAYED`, even when a separate replay later succeeds.

HTML `/repositories` exposes search and import configuration; protected
`POST /ui/repositories` accepts one URL per line, one revision and
source subdirectory for that batch, mode IMPORT or JAVA, idempotencyKey and an
optional discoveryId. Each repository receives a separate durable job and
per-item request identity. Selecting a discovery result binds its retained query
receipt to that URL; unrelated URLs cannot claim that discovery membership.
Direct imports omit discoveryId. Branches/tags/HEAD resolve to a recorded exact
commit at acquisition time. A queued import has the same cancellation/restart
ownership rules as other jobs. Author builds, private credentials, submodule/LFS
expansion and provider-backed observation are not implied.

Repository jobs expose `acquisition.json` (commit, selected source scope, Git
version, file hashes, declared Git modes, acquisition timing), optional linked
`discovery.json`, and retained Git stage error logs through the artifact allowlist.
The acquisition receipt distinguishes PINNED from fully RETAINED; failed or
cancelled attempts do not invent model/profile outcomes. Selecting Java for a
retained non-Java tree fails explicitly while preserving its acquisition receipt.

Errors return `{error}` with 400 for invalid/unsupported requests, 404 for
unknown identifiers, 409 for state/identity conflicts and 500 for internal
failures. The session filter may use standard HTTP error bodies for 401/403,
oversized bodies or unsupported content types. The browser handles both.


Batch admission no longer has an eight-project limit. `workbench.compute-workers`
controls execution capacity independently of the persisted FIFO backlog (default1).
`GET /api/v1/runs?group=KEY` returns the complete direct-submission group rather
than the generic latest100 window. Native multi-URL submissions redirect to that
group. `GET /api/v1/collections/reviews/ID/status` returns all submitted attempts
for the saved review in one metadata request. Pending cancellation reaches
CANCELLED immediately without launching a worker; active cancellation still
requires owned-process cleanup. Hard-loss reconciliation remains fail-closed.

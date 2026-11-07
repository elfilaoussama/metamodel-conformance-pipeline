# Configuration and troubleshooting

## Local deployment

Experiment Workspace is a single-operator local application. It binds to
`127.0.0.1`, uses same-origin session/CSRF checks and accepts files only under
the approved input root. It is not authenticated multiuser hosting. Do not expose
it through a public proxy as if it were a hosted service.

## Startup configuration

| Property | Meaning |
|---|---|
| `workbench.engine` | Absolute path to the qualified engine JAR; its hash is bound to attempts. |
| `workbench.input-root` | Existing trusted input directory. |
| `workbench.workspace` | Persistent local catalog, retained files, runs and inspection data. Must be disjoint from the input root. |
| `workbench.bridge-source` | Path to the matching `engine-bridge/ArtifactInspector.java`. |
| `workbench.compute-workers` | Positive worker-slot count; default 1. Each engine job runs in its own child process. |
| `server.port` | Local HTTP port; default 8765. |

These are server startup properties, not arbitrary command fields submitted by a
browser user. The Windows launcher accepts engine, input root, workspace, Java
home and port; additional Spring configuration can use its normal environment
or direct Java invocation. Keep the full JDK 17 available to compile the bridge.

Use `WORKBENCH_COMPUTE_WORKERS=2` in the server environment, or
`--workbench.compute-workers=2` with direct Java startup, to configure two slots.
Qualify capacity on the actual host before timing a study. More slots can increase
memory pressure and contention even when decisions remain equivalent.

## Attempt states

| State | Meaning and action |
|---|---|
| `QUEUED` | Persisted and waiting for a reserved worker slot. Cancellation does not need to launch a worker. |
| `RUNNING` | The dispatcher reserved a slot and the owned worker is starting or executing. Read its message. |
| `CANCELLING` | Cancellation was requested; owned processes are being stopped. |
| `COMPLETED` | The requested operation finished. Inspect its scientific decisions separately. |
| `FAILED` / `TIMED_OUT` | The operation did not complete successfully; inspect the failure/logs. |
| `CANCELLED` | The attempt was cancelled. No scientific success is implied. |
| `INTERRUPTED` | The service lost a nonterminal attempt. Reconcile ownership before launching more work. |

Closing the browser leaves server jobs running. Graceful server shutdown and
host loss are different. Interrupted ownership is fail-closed; this slice does
not silently resume interrupted jobs.

## Common problems

**GitHub search fails:** read the displayed validation or service error. Check the
generated query, connectivity, rate-limit metadata and HTTPS certificate trust.
Try the tutorial's exact repository query to isolate filtering from availability.
On Windows, `-UseSystemTrust` uses the Windows trust store without disabling TLS.

**A directory is rejected:** use an ordinary directory under the configured input
root. Symlinks, traversal, changed inventories and paths outside that root are
rejected. Keep the workspace elsewhere.

**A completed acquisition has no decisions:** acquisition retains source only.
Choose a supported analysis operation. Python/C++ analysis is not wired into the
web interface yet.

**A result contains unknowns:** read required evidence and diagnostics. Supply a
qualified supported input world or retain the unknown. Increasing a timeout does
not repair missing semantics.

**A job appears to stop updating:** distinguish unavailable polling from the
recorded job state. Open its attempt or refresh manually. Inspect server and worker
logs before retrying. Repeated submissions with the same identity return the
existing attempt; a changed experiment needs a new identity.

## Records and safe maintenance

Back up the workspace only with a coherent SQLite backup or while the service is
stopped and workers are reconciled. Preserve source inventories, result artifacts,
receipts, events, logs and timing. Do not treat these as disposable build caches.
Do not manually remove lock or ownership records to bypass recovery admission.

Per-job budgets and artifact limits are distinct from queue capacity. Java analysis
currently has a 120-second execution budget. Web inspection accepts artifacts up
to its configured contract bounds and source files up to 2 MiB; the web import
bound is narrower than some engine publication bounds. Consult the typed API and
implementation when configuring a different deployment.

# Sequential analysis runner

This Linux/JDK17 runner amortizes startup across a short sequential schedule. It
uses the unchanged engine JAR's ordinary analysis path and constructs a fresh
observer, pipeline and evaluator for each job. No verdicts, observations or Alloy
solutions are reused across jobs. Formal conditions and evidence gates remain in
the engine. This is local candidate tooling, not a published pipeline release.

From the repository root:

```bash
python3 -B tools/run-analysis-batch.py \
  --jobs /absolute/path/jobs.json \
  --jar target/metamodel-conformance-pipeline-next-0.16.0.jar \
  --output /new/path/batch-run
```

The output directory must be new and outside all analyzed source trees. The runner
copies and hashes the engine JAR there, compiles its small companion with JDK17,
and records the resolved inputs, tool executables/versions and operational settings.
It does not resolve dependencies or execute build providers. Supply existing
provider manifests when required.

The jobs JSON has exactly `schemaVersion: 1` and a nonempty `jobs` list. Each job
requires a unique nonempty `id`, a `source` path and `language` (`java`, `python`,
or `cpp`). Optional fields are `externalParents` (string list), `dependencyJars`
(path list), or `dependencyManifest` (path); jars and manifest are mutually
exclusive. Paths resolve relative to the manifest directory. IDs are metadata,
never filesystem paths. Each worker shares the declared Java toolchain and frozen
engine; frontend manifests continue to declare their own input contexts.

`--jobs-per-worker` defaults to 4, `--job-timeout` to 120 seconds,
`--worker-retries` to 1, and `--heap` to `1g`. These are recorded operational
controls, not semantic limits. A timeout records unfinished execution; it does not
manufacture a formal NOT_EVALUATED decision. `--java` and `--javac` can select
explicit JDK17 executables.

The supervisor records atomic receipts, one distinct directory per attempt,
worker logs, per-input heap/GC counters and process peak RSS. Opt-in `--gc-log`
adds detailed GC logs for diagnostic runs; it is disabled for ordinary execution.
Completed jobs survive later worker
failure. A crashed/timed-out active job can retry in a fresh worker; unstarted jobs
do not consume retries. An ordinary analysis error without a capsule is FAILED
and retires the worker before continuing other jobs. A capsule containing
NON_CONFORMANT or NOT_EVALUATED is a completed analysis, not an operational
failure. Process exit is followed by a final receipt drain. A partial artifact
without a completed receipt is never accepted.

`summary.json` records every job and attempt as PENDING, COMPLETED, FAILED or
UNFINISHED; attempt history also records NOT_STARTED. Exit 0 means all jobs
completed analysis; exit 1 means at least one failed/unfinished job. Exit 0 does
not imply conformance. Use the recorded attempt's `output` directory to locate its
capsule. All attempts and failure logs remain available for investigation.

Independent replay remains an explicit separate operation:

```bash
java -Xmx1g -jar /path/to/batch-run/engine.jar verify-capsule \
  --capsule /path/from/summary/verification-capsule.json
```

The runner records `independentReplay: NOT_REQUESTED`; it never labels a produced
capsule independently verified. The controlled integration gate performs fresh
replay for every successful output. Sources/provider dependencies remain ordinary
external inputs. Each capsule records its own observed facts and hashes.

Opt in to durable recovery with `--resumable` on the initial command. This adds an
ordinary observation pass for each job before analysis. The worker compares each
fresh analysis observation with that typed snapshot before publishing completion.
After hard supervisor or host loss, repeat the exact command with `--resume`
(the original `--resumable` flag is optional). An inherited Linux flock lease
blocks recovery while any old worker still runs. Recovery never kills an unknown
process by a persisted PID: allow the old worker to finish, or terminate its known
process group separately before resuming. The ordinary supervisor kills and waits
for its own process group on timeout or catchable interruption.

Resume checks the frozen engine/helper/JDK executable identities, resolved jobs
and operational settings, re-observes retained inputs with the ordinary observers,
and requires typed observation equality before reusing completed artifacts. This
covers recorded source/dependency changes; it does not certify full unreported
environment closure. Completed receipts bind XMI, Alloy and capsule hashes; missing
receipts or partial output never establish completion. Interrupted attempts remain
in history with censored timing. Charged unfinished attempts consume the same
finite retry budget across automatic retries and every explicit resume; repeating
`--resume` cannot reset an exhausted budget. A scheduled attempt with no STARTED
receipt does not consume a retry. Directories created before a summary publication
are retained and reported as unreconciled; later attempts use new directories.
Old output directories
without the new resumable binding cannot resume. Preparation can itself resume;
already retained preparation snapshots are revalidated first.

`lifecycle.json` records compilation/configuration, preparation/revalidation and
analysis/reconciliation cost per invocation, including total elapsed time. A killed
supervisor's unknown interval is explicitly censored, never reported as zero.
Independent replay remains separate and must be included in a campaign cost ledger.
There is no claim of a leak-free indefinitely resident worker. Short worker recycling
and GC/RSS records support further diagnosis without altering the model.

Tests:

```bash
python3 -B tools/batch/test_runner.py
python3 -B tools/batch/test_small_batch.py \
  --jar /path/to/frozen-engine.jar \
  --baseline /path/to/normal-build-pilot \
  --output /new/path/controlled-batch-gate
```

Process-boundary tests use fake workers to inject crashes, timeouts, missing
receipts and ordinary failures. They are execution-policy tests, not semantic
validation. The separate integration gate runs the actual engine on repeated
small Java/Python/C++ controls, with a deliberately absent source, complete
artifact comparisons and independent fresh replay. No large repositories are
needed.

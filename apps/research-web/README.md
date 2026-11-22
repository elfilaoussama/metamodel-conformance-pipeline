# Experiment Workspace

A local scientific interface to the current conformance engine, implemented with
Java 17, Spring Boot MVC and Thymeleaf. It learns from the earlier Swing workflow
without depending on the old pipeline. No logo, React application or Node runtime
is required.

The [manual](../../documentation/README.md) is the authoritative starting point.
The same Markdown pages are packaged in the application and available through
**Documentation** in the main navigation.

## Run the application

Build the independent server with a full JDK 17:

```powershell
./apps/research-web/build.ps1
./apps/research-web/start.ps1 `
  -Engine ./target/metamodel-conformance-pipeline-next-0.16.0.jar `
  -InputRoot E:/experiment-inputs `
  -Workspace E:/experiment-workspace `
  -JavaHome 'C:/Program Files/Java/jdk-17'
```

Use your actual qualified engine artifact and directories. Add `-UseSystemTrust`
to the build/launcher only when Windows certificate trust requires it. The
[first-analysis tutorial](../../documentation/tutorials/first-analysis.md)
provides preparation, a one-file source control and equivalent Java startup.

## Responsibilities

`server/` owns the local interface, durable configuration, queue and result views.
`engine-bridge/` reads typed artifacts with the configured qualified producer.
`tests/browser/` contains local browser qualification; Node is a development-only
dependency. The scientific engine remains in the root `src/` module.

Repository batches are independent of worker capacity. Waiting attempts remain in
SQLite; `workbench.compute-workers` controls separate worker processes. The default
is one. There is no eight-repository selection or submission limit. Scientific
producer behavior and evidence admission are independent of this scheduler.

## Read before reporting results

- [Architecture](../../documentation/explanation/architecture.md).
- [Scientific methodology](../../documentation/explanation/methodology.md).
- [Result interpretation](../../documentation/guides/interpret-results.md).
- [Capabilities and limitations](../../documentation/reference/capabilities.md).
- [Configuration and recovery boundaries](../../documentation/reference/operations.md).
- [Web API](../../documentation/reference/web-api.md).

The interface currently executes Java source-only analysis and separate formal
replay. It can acquire public repositories and inspect matching-engine recorded
Java/Python/C++ results. Python/C++ execution, author-build selection, custom
profiles and automatic host-loss recovery are not yet implemented here. A
completed attempt or a successful replay does not establish source fidelity.

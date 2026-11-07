# Your first analysis

This tutorial runs one Java class. It is intended to establish a working path
through the instrument before using larger projects. Do not start with a corpus.

## Prepare the environment

Use a full JDK 17, Maven 3.9 or later and Git. Build the engine and interface from
the repository root:

```sh
mvn --batch-mode --no-transfer-progress verify
mvn --batch-mode --no-transfer-progress -f apps/research-web/server/pom.xml package
```

The engine artifact version is defined in the root `pom.xml`. Use that exact
artifact, not an unrelated JAR left in an older build directory. The interface
JAR is `apps/research-web/server/target/research-workbench-0.1.0.jar`.

Create a trusted input directory and a separate workspace directory. Neither
directory may contain the other. Under the input directory, create
`example/Example.java`:

```java
package sample;
public class Example {
    private int value;
    public int value() { return value; }
}
```

## Start Experiment Workspace

On Windows, from the repository root:

```powershell
./apps/research-web/start.ps1 `
  -Engine ./target/metamodel-conformance-pipeline-next-0.15.0.jar `
  -InputRoot E:/experiment-inputs `
  -Workspace E:/experiment-workspace `
  -JavaHome 'C:/Program Files/Java/jdk-17'
```

Replace these paths with existing local directories. The example engine name
matches the current root POM; a frozen research campaign must identify its own
qualified producer. If the machine requires the Windows certificate store,
add `-UseSystemTrust`. This preserves TLS verification.

For other hosts, the equivalent Java invocation is:

```sh
java -jar apps/research-web/server/target/research-workbench-0.1.0.jar \
  --server.port=8765 \
  --workbench.engine=/absolute/path/producer.jar \
  --workbench.input-root=/absolute/path/inputs \
  --workbench.workspace=/absolute/path/workspace \
  --workbench.bridge-source=/absolute/path/repository/apps/research-web/engine-bridge/ArtifactInspector.java
```

Open `http://127.0.0.1:8765/workflow`. Keep the service running. Closing the browser
does not stop a submitted job; shutting down the service or host is different.

## Submit the example

1. Choose **Configure local analysis**.
2. Enter a run label and the full path to the `example` directory under the trusted
   input root.
3. Choose **Start analysis**. This creates a retained-source attempt. It does not
   overwrite a previous result.
4. Follow its state and recorded worker message. `QUEUED` means no worker slot is
   assigned yet. `RUNNING` means the worker owns the attempt. A spinner is not
   evidence of extraction progress; the recorded state is authoritative.

## Inspect the result

Open **Overview**, then **Decisions**. Read each rule's prerequisites and inspect
its witnesses. Use **Model**, **Evidence** and **Sources** to trace decisions to
the observed entities and retained file. **Artifacts** provides the original
files and available execution records.

For the qualified bundled profile this example is a positive control. Treat the
actual recorded profile and evidence as authoritative; do not assume a fixed
number of decisions for a different engine. A compiler, environment or profile
failure is a result to investigate, not permission to relabel unknowns.

## Verify the recorded decisions

Choose the separate replay action on the completed result. Replay starts a new
linked attempt and invokes the matching formal verifier. It does not read the
source again. See [Replay and reproduce](../guides/reproduce.md) before using
replay success as experimental evidence.

Next, read [Interpret results](../guides/interpret-results.md) and
[Capabilities and limitations](../reference/capabilities.md).

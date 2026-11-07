#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-gradle-dependencies.sh source-root output-manifest" >&2
  exit 64
fi
source_root="$1"
output_file="$2"
if [[ ! -d "$source_root" || -L "$source_root" ]]; then
  echo "source root is not a regular directory: $source_root" >&2
  exit 66
fi
source_root="$(cd "$source_root" && pwd -P)"
mkdir -p "$(dirname "$output_file")"
: > "$output_file"

# Relocation-independent digest retaining relative paths and file contents.
# Two compilation runs must agree on it before any generated evidence is kept.
generated_hash() {
  python3 "$script_dir/generated-input-digest.py" "$1"
}

mapfile -d '' settings_files < <(find "$source_root" -type f \( -name settings.gradle -o -name settings.gradle.kts \) \
  -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/build/*' -print0 | sort -z)
declare -A settings_roots=()
for settings in "${settings_files[@]}"; do settings_roots["$(dirname "$settings")"]=1; done

declare -A build_roots_map=()
for root in "${!settings_roots[@]}"; do build_roots_map["$root"]=1; done
mapfile -d '' build_files < <(find "$source_root" -type f \( -name build.gradle -o -name build.gradle.kts \) \
  -not -path '*/.git/*' -not -path '*/.gradle/*' -not -path '*/build/*' -print0 | sort -z)
for build_file in "${build_files[@]}"; do
  dir="$(dirname "$build_file")"; claimed=0
  for settings_root in "${!settings_roots[@]}"; do
    if [[ "$dir" == "$settings_root" || "$dir" == "$settings_root/"* ]]; then claimed=1; break; fi
  done
  [[ $claimed -eq 1 ]] || build_roots_map["$dir"]=1
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
[[ ${#build_roots_map[@]} -gt 0 ]] || exit 0
mapfile -t build_roots < <(printf '%s\n' "${!build_roots_map[@]}" | sort)

resolver_image="${GRADLE_RESOLVER_IMAGE:-}"
[[ -n "$resolver_image" ]] || { echo "GRADLE_RESOLVER_IMAGE is required for isolated Gradle resolution" >&2; exit 64; }
[[ "$resolver_image" =~ ^[0-9A-Za-z._/:@-]+$ ]] || { echo "invalid GRADLE_RESOLVER_IMAGE" >&2; exit 64; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required to isolate Gradle dependency resolution" >&2; exit 69; }

resolution_root="${output_file}.cache"
rm -rf -- "$resolution_root"
mkdir -p "$resolution_root/home" "$resolution_root/gradle" "$resolution_root/result" "$resolution_root/tmp" "$resolution_root/worktree"

# Gradle configuration can create project-local state (reports, caches, and
# toolchain metadata). Run it on a private writable copy so the original
# evidence mount remains read-only for every build.
worktree_root="$resolution_root/worktree"
(
  cd "$source_root"
  tar --exclude='./.git' --exclude='./.gradle' --exclude='./build' -cf - .
) | tar -C "$worktree_root" -xf -
chmod -R u+rwX "$worktree_root"

container_source=/workspace/source
container_worktree=/workspace/worktree
container_out=/workspace/out
container_home="$container_out/home"
container_gradle_home="$container_out/gradle"
container_tmp="$container_out/tmp"
# Gradle wrappers read the unqualified networkTimeout system property. A
# transient slow distribution download must not turn an otherwise reproducible
# build context into missing dependency evidence. This is delivery tolerance,
# not compilation or observation semantics.
gradle_runtime_opts="-Djava.io.tmpdir=$container_tmp -DnetworkTimeout=60000"
container_init=/workspace/mcp-init.gradle
init_script="$resolution_root/mcp-init.gradle"

cat > "$init_script" <<'GRADLE'
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.JavaVersion
import java.util.regex.Pattern

def repositoryRoot = new File(System.getProperty('mcp.repository.root')).canonicalFile
def configuredBuildOutput = System.getProperty('mcp.build.output')
if (configuredBuildOutput == null || configuredBuildOutput.trim().isEmpty()) {
    throw new GradleException('mcp.build.output is required')
}
def buildOutputRoot = new File(configuredBuildOutput).canonicalFile

def rel = { File value ->
    def canonical = value.canonicalFile
    def rootPath = repositoryRoot.toPath()
    def valuePath = canonical.toPath()
    if (!valuePath.startsWith(rootPath)) {
        throw new GradleException("path escapes repository root: ${canonical}")
    }
    def text = rootPath.relativize(valuePath).toString().replace('\\', '/')
    return text.isEmpty() ? '.' : text
}
def level = { Object value ->
    if (value == null) return ''
    try { return JavaVersion.toVersion(value).majorVersion } catch (Throwable ignored) { return '' }
}
def safe = { String value -> value.replace('\t',' ').replace('\n',' ').replace('\r',' ') }

// A source set declares annotation processing through its processor
// configuration; only declared dependencies are inspected (no resolution).
def declaredAnnotationProcessor = { project, sourceSet ->
    try {
        def configuration = project.configurations.findByName(sourceSet.annotationProcessorConfigurationName)
        return configuration != null && !configuration.allDependencies.isEmpty()
    } catch (Throwable ignored) {
        return false
    }
}

// The directory a Java compile task writes annotation-processor generated
// sources to. Newer Gradle exposes it as a task option; older builds use the
// conventional location beneath the (relocated) build directory.
def generatedSourceDir = { project, sourceSet, compileTask ->
    if (!(compileTask instanceof JavaCompile)) return null
    try {
        def property = compileTask.options.generatedSourceOutputDirectory
        if (property != null) {
            def value = property.getAsFile().getOrNull()
            if (value != null) return value
        }
    } catch (Throwable ignored) { }
    def buildDir = project.layout.buildDirectory.getAsFile().getOrNull()
    if (buildDir == null) return null
    return new File(buildDir, 'generated/sources/annotationProcessor/java/' + sourceSet.name)
}

def rootObservationTask = null

gradle.beforeProject { project ->
    def relativeProjectDir = rel(project.projectDir)
    def outputDir = new File(buildOutputRoot, relativeProjectDir == '.' ? 'root' : relativeProjectDir)
    project.layout.buildDirectory.set(outputDir)
    def observation = project.tasks.register('__mcpObserveJavaContexts') { }
    // Each project observes its own source sets: a project may resolve its own
    // configurations at task execution, while Gradle 9 rejects resolving another
    // project's configuration there (notably under parallel execution). Every
    // project writes a fragment; the root task aggregates fragments after every
    // archive-producing dependency has run.
    observation.configure {
        doLast {
            def outputPath = System.getProperty('mcp.context.output')
            if (outputPath == null || outputPath.trim().isEmpty()) throw new GradleException('mcp.context.output is required')
            def partsDir = new File(outputPath + '.parts')
            partsDir.mkdirs()
            def key = project.path == ':' ? '%3A' : project.path.replace('%', '%25').replace(':', '%3A')
            def fragment = new File(partsDir, key + '.tsv')
            fragment.text = ''
            def generatedSidecar = new File(partsDir, key + '.generated')
            generatedSidecar.text = ''
            def sourceSets = project.extensions.findByType(SourceSetContainer)
            if (sourceSets == null) return
            sourceSets.sort { a, b -> a.name <=> b.name }.each { sourceSet ->
                def roots = sourceSet.java.srcDirs.findAll { dir ->
                    try { dir.canonicalFile.toPath().startsWith(repositoryRoot.toPath()) } catch (Throwable ignored) { false }
                }.sort { a, b -> a.canonicalPath <=> b.canonicalPath }
                def hasJava = roots.any { dir -> dir.isDirectory() && !project.fileTree(dir).matching { include '**/*.java' }.files.isEmpty() }
                if (!hasJava) return
                def compileTask = project.tasks.findByName(sourceSet.compileJavaTaskName)
                def release = ''
                def sourceLevel = ''
                def targetLevel = ''
                def preview = false
                def platform = ''
                if (compileTask instanceof JavaCompile) {
                    try {
                        def r = compileTask.options.release.orNull
                        if (r != null) release = r.toString()
                    } catch (Throwable ignored) { }
                    if (release.isEmpty()) {
                        sourceLevel = level(compileTask.sourceCompatibility)
                        targetLevel = level(compileTask.targetCompatibility)
                    }
                    preview = compileTask.options.compilerArgs.contains('--enable-preview')
                    // Do not materialize javaCompiler: that can provision a toolchain
                    // solely to inspect metadata. An unavailable executable compiler is
                    // represented by absent platform evidence, never guessed semantics.
                }
                def id = safe("gradle|${rel(project.projectDir)}|${project.path}|${sourceSet.name}")
                def entries = sourceSet.compileClasspath.files.collect { it.canonicalFile }
                def outputs = sourceSet.output.classesDirs.files.collect { it.canonicalFile }
                // A source set that compiles a module descriptor needs its
                // dependency archives on the module path: a classpath archive
                // stays in the unnamed module and cannot satisfy requires
                // clauses. Gradle compiler-exclusion facts are not observed
                // yet, so descriptor presence alone selects the module path.
                def modular = roots.any { dir -> new File(dir, 'module-info.java').isFile() }
                def resolutionRole = modular ? 'module-path' : 'classpath'
                fragment << "context\t${id}\t${rel(project.projectDir)}\t${sourceLevel}\t${targetLevel}\t${release}\t${preview}\t${platform}" + System.lineSeparator()
                roots.collect(rel).each { root -> fragment << "source\t${id}\t${root}" + System.lineSeparator() }
                outputs.sort { a, b -> a.path <=> b.path }.each { out ->
                    fragment << "output\t${id}\t${out.path}" + System.lineSeparator()
                }
                entries.each { entry -> fragment << "${resolutionRole}\t${id}\t${entry.path}" + System.lineSeparator() }
                // Declared annotation processing produces generated sources for
                // this source set. They are materialized into a stable location
                // inside the repository so they can be named repository-relative,
                // and a sidecar records the declared output and compile task for
                // the host-side determinism check (which reruns the compilation).
                if (declaredAnnotationProcessor(project, sourceSet)) {
                    def generatedDir = generatedSourceDir(project, sourceSet, compileTask)
                    if (generatedDir != null && generatedDir.isDirectory()) {
                        def generatedJava = project.fileTree(generatedDir).matching { include '**/*.java' }.files
                        if (!generatedJava.isEmpty()) {
                            def synthDir = new File(repositoryRoot, '__mcp_generated__/' + key + '/' + sourceSet.name)
                            if (synthDir.exists() && !synthDir.deleteDir()) {
                                throw new GradleException("cannot clear generated synthesis directory: ${synthDir}")
                            }
                            generatedJava.each { file ->
                                def relative = generatedDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                                def destination = new File(synthDir, relative)
                                destination.parentFile.mkdirs()
                                destination.bytes = file.bytes
                            }
                            def generatedRelative = rel(synthDir)
                            fragment << "generated-source\t${id}\t${generatedRelative}" + System.lineSeparator()
                            generatedSidecar <<
                                    "${id}\t${generatedRelative}\t${compileTask.path}\t${generatedDir.absolutePath}" + System.lineSeparator()
                        }
                    }
                }
            }
        }
    }
    if (project == gradle.rootProject) {
        rootObservationTask = observation
    }
    // Compilation classpaths may reference project-produced archives
    // (test-fixture or explicitly requested jar variants). Their
    // producing tasks must run in this isolated worktree so the
    // manifest points at authentic built artifacts instead of
    // missing paths; dropping the entry or substituting a classes
    // directory would fabricate evidence.
    project.afterEvaluate {
        def sourceSets = project.extensions.findByType(SourceSetContainer)
        if (sourceSets == null) return
        sourceSets.each { sourceSet ->
            observation.configure {
                dependsOn(sourceSet.compileClasspath.buildDependencies)
            }
            // Declared annotation processing generates sources during this
            // source set's compilation, so the compile task must run before
            // the observing fragment reads its generated output directory.
            if (declaredAnnotationProcessor(project, sourceSet)) {
                def compileTask = project.tasks.findByName(sourceSet.compileJavaTaskName)
                if (compileTask != null) {
                    observation.configure {
                        dependsOn(compileTask)
                    }
                }
            }
        }
    }
}

gradle.projectsEvaluated {
    if (rootObservationTask == null) return
    def observation = rootObservationTask
    observation.configure {
        dependsOn(gradle.rootProject.allprojects.collect { project ->
            project.path == ':' ? ':__mcpObserveJavaContexts' : project.path + ':__mcpObserveJavaContexts'
        }.findAll { it != ':__mcpObserveJavaContexts' })
    }
    observation.configure {
        doLast {
            def outputPath = System.getProperty('mcp.context.output')
            if (outputPath == null || outputPath.trim().isEmpty()) throw new GradleException('mcp.context.output is required')
            def partsDir = new File(outputPath + '.parts')
            def contexts = []
            if (partsDir.isDirectory()) {
                partsDir.listFiles().findAll { it.isFile() && it.name.endsWith('.tsv') }.sort { it.name <=> it.name }.each { fragment ->
                    def current = null
                    fragment.eachLine { line ->
                        def fields = line.split('\t', -1)
                        if (fields[0] == 'context') {
                            current = [id:fields[1], module:fields[2], source:fields[3], target:fields[4],
                                       release:fields[5], preview:fields[6], platform:fields[7],
                                       roots:[], outputs:[], entries:[], generated:[]]
                            contexts << current
                        } else if (current != null && fields[0] == 'source') {
                            current.roots << fields[2]
                        } else if (current != null && fields[0] == 'generated-source') {
                            current.generated << fields[2]
                        } else if (current != null && fields[0] == 'output') {
                            current.outputs << new File(fields[2])
                        } else if (current != null && (fields[0] == 'classpath' || fields[0] == 'module-path')) {
                            current.entries << [file:new File(fields[2]), role:fields[0]]
                        }
                    }
                }
            }
            def output = new File(outputPath)
            output.parentFile.mkdirs()
            output.text = ''
            def outputOwners = [:]
            contexts.each { ctx -> ctx.outputs.each { out ->
                if (!outputOwners.containsKey(out.path)) outputOwners[out.path] = [] as Set
                outputOwners[out.path].add(ctx.id)
            } }
            // Only an exact observed classes-directory identity establishes context
            // ownership. Project identity or build-directory containment cannot identify
            // archive variants (test fixtures, custom/shaded JARs, generated resources).
            // Preserve archives, including unresolved ones, for fail-closed validation.
            contexts.sort { a, b -> a.id <=> b.id }.each { ctx ->
                output << "context\t${ctx.id}\t${ctx.module}\t${ctx.source}\t${ctx.target}\t${ctx.release}\t${ctx.preview}\t${ctx.platform}" + System.lineSeparator()
                ctx.roots.each { root -> output << "source\t${ctx.id}\t${root}" + System.lineSeparator() }
                ctx.generated.each { generated -> output << "generated-source\t${ctx.id}\t${generated}" + System.lineSeparator() }
                ctx.outputs.sort { a, b -> a.path <=> b.path }.each { out ->
                    output << "output\t${ctx.id}\t${out.path}" + System.lineSeparator()
                }
                def seenUpstream = [] as Set
                ctx.entries.each { entry ->
                    def owners = outputOwners[entry.file.path]
                    if (owners != null && owners.size() > 1) {
                        throw new IllegalStateException('Ambiguous compilation-context output ownership: '
                                + entry.file.path + ' -> ' + owners.toList().sort().join(', '))
                    }
                    def owner = owners == null ? null : owners.iterator().next()
                    if (owner != null && owner != ctx.id) {
                        if (seenUpstream.add(owner)) output << "upstream\t${ctx.id}\t${owner}" + System.lineSeparator()
                    }
                    // Ownership adds a compilation dependency; it must not erase
                    // the output's observed position relative to external entries.
                    output << "${entry.role}\t${ctx.id}\t${entry.file.path}" + System.lineSeparator()
                }
            }
        }
    }
}
GRADLE
chmod a-w "$init_script"

# Runs the isolated Gradle observer for the current build root. Extra arguments
# are appended to the Gradle invocation (for example --rerun-tasks). It reads the
# per-iteration variables, which are set before each call.
run_gradle_resolver() {
  docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
    --tmpfs /tmp:rw,nosuid,nodev,noexec \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$source_root,dst=$container_source,readonly" \
    --mount "type=bind,src=$worktree_root,dst=$container_worktree" \
    --mount "type=bind,src=$resolution_root,dst=$container_out" \
    --mount "type=bind,src=$init_script,dst=$container_init,readonly" \
    -e HOME="$container_home" -e GRADLE_USER_HOME="$container_gradle_home" \
    -e JAVA_TOOL_OPTIONS="$gradle_runtime_opts" \
    -w "$container_build" "$resolver_image" "${gradle_command[@]}" \
    --project-cache-dir "$container_project_cache" --no-daemon --no-configuration-cache --no-configure-on-demand --console=plain --stacktrace --init-script "$container_init" \
    -Dmcp.repository.root="$container_worktree" -Dmcp.context.output="$container_raw" -Dmcp.build.output="$container_build_output" \
    "$@"
}

for index in "${!build_roots[@]}"; do
  build_root="${build_roots[$index]}"
  build_key="$(realpath --relative-to="$source_root" "$build_root")"
  [[ "$build_key" != /* && "$build_key" != ../* && "$build_key" != *'/../'* ]] \
    || { echo "Gradle build root escaped source root: $build_key" >&2; exit 70; }
  [[ "$build_key" == . ]] && container_build="$container_worktree" || container_build="$container_worktree/$build_key"
  raw="$resolution_root/result/context-$index.tsv"; container_raw="$container_out/result/context-$index.tsv"
  project_cache="$resolution_root/project-cache-$index"; container_project_cache="$container_out/project-cache-$index"
  build_output="$resolution_root/build-$index"; container_build_output="$container_out/build-$index"
  mkdir -p "$project_cache" "$build_output"
  # Use the nearest non-symlink Gradle wrapper at or above the discovered build
  # root. Included builds commonly omit their own wrapper but are still governed
  # by the root build's pinned Gradle version.
  wrapper_root="$build_root"
  while [[ "$wrapper_root" != "$source_root" \
      && ( ! -f "$wrapper_root/gradlew" || -L "$wrapper_root/gradlew" ) ]]; do
    parent="$(dirname "$wrapper_root")"
    [[ "$parent" != "$wrapper_root" ]] || break
    wrapper_root="$parent"
  done
  if [[ -f "$wrapper_root/gradlew" && ! -L "$wrapper_root/gradlew" ]]; then
    wrapper_relative="$(realpath --relative-to="$source_root" "$wrapper_root/gradlew")"
    [[ "$wrapper_relative" != /* && "$wrapper_relative" != ../* && "$wrapper_relative" != *'/../'* ]] \
      || { echo "Gradle wrapper escaped source root: $wrapper_relative" >&2; exit 70; }
    gradle_command=(sh "$container_worktree/$wrapper_relative")
  else
    gradle_command=(gradle)
  fi

  run_gradle_resolver __mcpObserveJavaContexts
  [[ -f "$raw" ]] || { echo "isolated Gradle resolver produced no context manifest for $build_key" >&2; exit 70; }

  # Declared annotation-processor outputs are only kept if materialization is
  # byte-stable. Each recorded generated tree is hashed, the compilation is
  # forced to rerun, and the trees are compared; any unstable or unreproducible
  # output is dropped from the manifest rather than recorded as evidence.
  parts_dir="$resolution_root/result/context-$index.tsv.parts"
  non_deterministic=""
  generated_sidecars=()
  if [[ -d "$parts_dir" ]]; then
    while IFS= read -r -d '' sidecar; do generated_sidecars+=("$sidecar"); done \
      < <(find "$parts_dir" -type f -name '*.generated' -print0 | sort -z)
  fi
  if [[ ${#generated_sidecars[@]} -gt 0 ]]; then
    declare -A generation_before=()
    while IFS=$'\t' read -r gid grel gtask gdir; do
      [[ -n "$gid" && -n "$grel" ]] || continue
      generation_before["$gid"]="$(generated_hash "$worktree_root/$grel")"
    done < <(cat "${generated_sidecars[@]}")
    if [[ ${#generation_before[@]} -gt 0 ]]; then
      if ! run_gradle_resolver __mcpObserveJavaContexts --rerun-tasks >> "$resolution_root/generation.log" 2>&1; then
        echo "generated-source repeat compilation failed: $build_key" >&2
        for gid in "${!generation_before[@]}"; do
          non_deterministic+="${non_deterministic:+,}$gid"
        done
      else
        while IFS=$'\t' read -r gid grel gtask gdir; do
          [[ -n "$gid" && -n "$grel" ]] || continue
          if [[ -z "${generation_before[$gid]:-}" \
              || "${generation_before[$gid]}" != "$(generated_hash "$worktree_root/$grel")" ]]; then
            echo "generated-source output is not deterministic: $gtask ($build_key)" >&2
            non_deterministic+="${non_deterministic:+,}$gid"
          fi
        done < <(cat "${generated_sidecars[@]}")
      fi
    fi
    unset generation_before
  fi

  python3 - "$raw" "$output_file" "$source_root" "$resolution_root" "$worktree_root" "$container_source" "$container_worktree" "$container_out" "$non_deterministic" <<'PYHOST'
import os
import sys

raw, output, source_root, resolution_root, worktree_root, container_source, container_worktree, container_out, rejected = sys.argv[1:]

rejected_contexts = {value for value in rejected.split(',') if value}

def host_path(value):
    if value.startswith(container_out + '/'):
        return os.path.normpath(os.path.join(resolution_root, value[len(container_out) + 1:]))
    if value.startswith(container_source + '/'):
        return os.path.normpath(os.path.join(source_root, value[len(container_source) + 1:]))
    if value.startswith(container_worktree + '/'):
        return os.path.normpath(os.path.join(worktree_root, value[len(container_worktree) + 1:]))
    raise SystemExit(f"resolved Gradle path escaped isolated mount boundaries: {value}")

with open(raw, encoding='utf-8') as source, open(output, 'a', encoding='utf-8') as sink:
    for number, line in enumerate(source, 1):
        fields = line.rstrip('\n').rstrip('\r').split('\t')
        if not fields or not fields[0]:
            continue
        kind = fields[0]
        if kind == 'context':
            if len(fields) != 8 or not fields[1] or not fields[2]:
                raise SystemExit(f"invalid Gradle context row {number}")
            if fields[6] not in ('true', 'false'):
                raise SystemExit(f"invalid Gradle preview flag on row {number}")
            sink.write('\t'.join(fields) + '\n')
        elif kind in ('source', 'generated-source', 'upstream'):
            if len(fields) != 3 or not fields[1] or not fields[2]:
                raise SystemExit(f"invalid Gradle {kind} row {number}")
            if kind == 'generated-source' and fields[1] in rejected_contexts:
                continue
            sink.write('\t'.join(fields) + '\n')
        elif kind in ('classpath', 'module-path', 'processor-path', 'upgrade-module-path', 'platform-path', 'output'):
            if len(fields) != 3 or not fields[1] or not fields[2]:
                raise SystemExit(f"invalid Gradle {kind} row {number}")
            sink.write('\t'.join((kind, fields[1], host_path(fields[2]))) + '\n')
        elif kind == 'patch-module':
            if len(fields) != 4 or not fields[1] or not fields[2] or not fields[3]:
                raise SystemExit(f"invalid Gradle patch-module row {number}")
            sink.write('\t'.join((kind, fields[1], fields[2], host_path(fields[3]))) + '\n')
        else:
            raise SystemExit(f"invalid Gradle resolver row kind on row {number}: {kind}")
PYHOST
done

"$script_dir/validate-java-dependency-manifest.sh" "$output_file"

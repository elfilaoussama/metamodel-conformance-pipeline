#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-maven-dependencies.sh source-root output-manifest" >&2
  exit 64
fi
source_root="$1"; output_file="$2"
if [[ ! -d "$source_root" || -L "$source_root" ]]; then
  echo "source root is not a regular directory: $source_root" >&2; exit 66
fi
source_root="$(cd "$source_root" && pwd -P)"
mkdir -p "$(dirname "$output_file")"; : > "$output_file"

mapfile -d '' poms < <(find "$source_root" -type f -name pom.xml \
  -not -path '*/.git/*' -not -path '*/target/*' -print0 | sort -z)
[[ ${#poms[@]} -gt 0 ]] || exit 0

resolver_image="${MAVEN_RESOLVER_IMAGE:-}"
dependency_goal="${MAVEN_DEPENDENCY_CLASSPATH_GOAL:-}"
[[ -n "$resolver_image" ]] || { echo "MAVEN_RESOLVER_IMAGE is required for isolated Maven resolution" >&2; exit 64; }
[[ "$resolver_image" =~ ^[0-9A-Za-z._/:@-]+$ ]] || { echo "invalid MAVEN_RESOLVER_IMAGE" >&2; exit 64; }
[[ -n "$dependency_goal" ]] || { echo "MAVEN_DEPENDENCY_CLASSPATH_GOAL is required" >&2; exit 64; }
[[ "$dependency_goal" != *$'\t'* && "$dependency_goal" != *$'\n'* ]] || { echo "invalid Maven dependency goal" >&2; exit 64; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required to isolate Maven resolution" >&2; exit 69; }

resolution_root="${output_file}.cache"
rm -rf -- "$resolution_root"
mkdir -p "$resolution_root/home/.m2" "$resolution_root/repository" "$resolution_root/result" \
  "$resolution_root/tmp" "$resolution_root/jansi"
container_source=/workspace/source
container_out=/workspace/out
container_home="$container_out/home"
container_maven_config="$container_home/.m2"
container_tmp="$container_out/tmp"
container_jansi="$container_out/jansi"
maven_runtime_opts="-Djava.io.tmpdir=$container_tmp -Djansi.tmpdir=$container_jansi -Djansi.force=false -Djansi.passthrough=true"

container_path() {
  local host="$1"
  if [[ "$host" == "$source_root" ]]; then printf '%s\n' "$container_source"
  elif [[ "$host" == "$source_root/"* ]]; then printf '%s/%s\n' "$container_source" "${host#$source_root/}"
  else echo "path escapes source root: $host" >&2; return 70; fi
}

# Order- and path-independent content digest of a declared generated output.
# Two generation runs must agree on it before any generated evidence is kept.
generated_hash() {
  find "$1" -type f -print0 2>/dev/null | sort -z | xargs -0 -r sha256sum 2>/dev/null \
    | awk '{print $1}' | sort | sha256sum | awk '{print $1}'
}

for index in "${!poms[@]}"; do
  pom="${poms[$index]}"; module_dir="$(dirname "$pom")"
  module_key="$(realpath --relative-to="$source_root" "$module_dir")"
  [[ "$module_key" != /* && "$module_key" != ../* && "$module_key" != *'/../'* ]] \
    || { echo "Maven module escaped source root: $module_key" >&2; exit 70; }
  container_pom="$(container_path "$pom")"
  effective="$resolution_root/result/effective-$index.xml"
  container_effective="$container_out/result/effective-$index.xml"
  generators_file="$resolution_root/result/generators-$index.tsv"
  : > "$generators_file"

  docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
    --tmpfs /tmp:rw,nosuid,nodev,noexec \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$source_root,dst=$container_source,readonly" \
    --mount "type=bind,src=$resolution_root,dst=$container_out" \
    -e HOME="$container_home" -e MAVEN_CONFIG="$container_maven_config" \
    -e MAVEN_OPTS="$maven_runtime_opts" -w "$container_source" "$resolver_image" \
    mvn --batch-mode --no-transfer-progress -q -f "$container_pom" \
      -Dmaven.repo.local="$container_out/repository" \
      help:effective-pom -Doutput="$container_effective"
  [[ -f "$effective" ]] || { echo "Maven effective model unavailable for $module_key" >&2; exit 70; }

  metadata="$resolution_root/result/metadata-$index.tsv"
  python3 - "$effective" "$module_key" "$container_source" "$generators_file" > "$metadata" <<'PY'
import sys, xml.etree.ElementTree as ET
path,module,root,generators_path=sys.argv[1:]
tree=ET.parse(path); r=tree.getroot()
def local(tag): return tag.rsplit('}',1)[-1]
def child(node,name):
    if node is None: return None
    return next((x for x in node if local(x.tag)==name),None)
def text(node,name):
    x=child(node,name); return '' if x is None or x.text is None else x.text.strip()
build=child(r,'build')
source=text(build,'sourceDirectory'); test=text(build,'testSourceDirectory')
out=text(build,'outputDirectory'); testout=text(build,'testOutputDirectory')
properties=child(r,'properties')
def prop(name): return text(properties,name)
compiler_cfg=compile_cfg=test_cfg=None
plugins=child(build,'plugins')
if plugins is not None:
    for plugin in plugins:
        if local(plugin.tag)!='plugin': continue
        if text(plugin,'artifactId')!='maven-compiler-plugin': continue
        compiler_cfg=child(plugin,'configuration')
        executions=child(plugin,'executions')
        if executions is not None:
            for execution in executions:
                if local(execution.tag)!='execution': continue
                goals_node=child(execution,'goals')
                goals=[] if goals_node is None else [
                    (item.text or '').strip() for item in goals_node if local(item.tag)=='goal'
                ]
                cfg=child(execution,'configuration')
                if 'compile' in goals: compile_cfg=cfg
                if 'testCompile' in goals: test_cfg=cfg
        break
def first(*values): return next((v for v in values if v), '')
def setting(execution_cfg, name, *property_names):
    return first(text(execution_cfg,name), text(compiler_cfg,name), *(prop(p) for p in property_names))
def has_preview(*configs):
    for cfg in configs:
        args=child(cfg,'compilerArgs')
        if args is not None and any((x.text or '').strip()=='--enable-preview' for x in args): return 'true'
    return 'false'
def compiler_arguments(*configs):
    values=[]
    skip_next=False
    for cfg in configs:
        args=child(cfg,'compilerArgs')
        if args is None: continue
        for item in args:
            if local(item.tag)!='arg': continue
            value=(item.text or '').strip()
            if not value: continue
            if skip_next:
                skip_next=False
                continue
            # Options already reconstructed from the observed semantics are not
            # repeated here; provider-declared extras (for example --add-exports)
            # are preserved verbatim.
            if value in ('--release','-source','-target','--enable-preview',
                         '-classpath','-cp','-d'):
                continue
            # Compiler plugins and annotation processors are not replayed: their
            # processor path is not part of the observed dependency evidence, and
            # a missing plugin would abort javac.
            if value in ('-processor','-processorpath','--processor-path',
                         '--processor-module-path'):
                skip_next=True
                continue
            if value.startswith(('-Xplugin','-Xep','-A')):
                continue
            # A single Maven <arg> may carry embedded newlines (multi-flag plugin
            # configuration); such a value cannot be represented as a manifest row.
            if any(ch in value for ch in ('\t','\n','\r')):
                continue
            values.append(value)
    return values
def major(v):
    v=(v or '').strip()
    if not v: return ''
    if v.startswith('1.') and v[2:].isdigit(): return str(int(v[2:]))
    if v.isdigit(): return str(int(v))
    return ''
compile_release=major(setting(compile_cfg,'release','maven.compiler.release'))
compile_source=major(setting(compile_cfg,'source','maven.compiler.source'))
compile_target=major(setting(compile_cfg,'target','maven.compiler.target'))
if compile_release: compile_source=compile_target=''
test_release=major(first(
    text(test_cfg,'release'), text(test_cfg,'testRelease'),
    text(compiler_cfg,'testRelease'), prop('maven.compiler.testRelease'), compile_release))
test_source_level=major(first(
    text(test_cfg,'source'), text(test_cfg,'testSource'),
    text(compiler_cfg,'testSource'), prop('maven.compiler.testSource'), compile_source))
test_target_level=major(first(
    text(test_cfg,'target'), text(test_cfg,'testTarget'),
    text(compiler_cfg,'testTarget'), prop('maven.compiler.testTarget'), compile_target))
if test_release: test_source_level=test_target_level=''
compile_preview=has_preview(compile_cfg,compiler_cfg)
test_preview=has_preview(test_cfg,compiler_cfg)
def exclusions(cfg, *names):
    values=[]
    for name in names:
        node=child(cfg,name)
        if node is None: continue
        values.extend((item.text or '').strip() for item in node
                      if local(item.tag)=='exclude' and (item.text or '').strip())
    return values
compile_excludes=exclusions(compile_cfg,'excludes')
test_excludes=exclusions(test_cfg,'testExcludes','excludes')
compile_arguments=compiler_arguments(compile_cfg,compiler_cfg)
test_arguments=compiler_arguments(test_cfg,compiler_cfg)
def templating_source_directory():
    # A supported plugin profile: the templating plugin's input tree contains
    # Java-looking template files that no observed compiler execution consumes.
    plugins=child(build,'plugins')
    if plugins is None: return ''
    for plugin in plugins:
        if local(plugin.tag)!='plugin': continue
        if text(plugin,'artifactId')!='templating-maven-plugin': continue
        value=text(child(plugin,'configuration'),'sourceDirectory')
        if value: return value
        executions=child(plugin,'executions')
        if executions is not None:
            for execution in executions:
                if local(execution.tag)!='execution': continue
                value=text(child(execution,'configuration'),'sourceDirectory')
                if value: return value
    return ''
templates=templating_source_directory()
def normalize(v):
    if not v: return ''
    # Effective Maven paths may use host-independent /workspace/source values in the container.
    return v.replace('\\','/')
def plugin_executions(artifact_id, goal_name):
    results=[]
    plugins=child(build,'plugins')
    if plugins is None: return results
    for plugin in plugins:
        if local(plugin.tag)!='plugin': continue
        if text(plugin,'artifactId')!=artifact_id: continue
        version=text(plugin,'version')
        plugin_cfg=child(plugin,'configuration')
        executions=child(plugin,'executions')
        if executions is None: continue
        for execution in executions:
            if local(execution.tag)!='execution': continue
            goals_node=child(execution,'goals')
            goals=[] if goals_node is None else [
                (item.text or '').strip() for item in goals_node if local(item.tag)=='goal'
            ]
            if goal_name not in goals: continue
            results.append((text(execution,'id'), version, child(execution,'configuration'), plugin_cfg))
    return results
def build_parent(output_directory):
    value=normalize(output_directory)
    if not value or '/' not in value: return ''
    return value.rsplit('/',1)[0]
generators=[]
for exec_id, version, exec_cfg, plugin_cfg in plugin_executions('templating-maven-plugin','filter-sources'):
    src=first(text(exec_cfg,'sourceDirectory'), text(plugin_cfg,'sourceDirectory'))
    out_dir=first(text(exec_cfg,'outputDirectory'), text(plugin_cfg,'outputDirectory'))
    if exec_id and out_dir:
        generators.append(('templating',exec_id,'filter-sources',version,normalize(src),
                           normalize(out_dir),build_parent(out)))
for exec_id, version, exec_cfg, plugin_cfg in plugin_executions('replacer','replace'):
    src=first(text(exec_cfg,'file'), text(plugin_cfg,'file'))
    out_file=first(text(exec_cfg,'outputFile'), text(plugin_cfg,'outputFile'))
    if exec_id and out_file:
        generators.append(('replacer',exec_id,'replace',version,normalize(src),
                           normalize(out_file),build_parent(out)))
with open(generators_path,'w',encoding='utf-8') as handle:
    for record in generators:
        handle.write('\t'.join(record)+'\n')
sep=chr(31)
records=chr(30)
print(sep.join(['compile',source,out,compile_source,compile_target,compile_release,compile_preview,
                records.join(compile_excludes),records.join(compile_arguments)]))
print(sep.join(['test',test,testout,test_source_level,test_target_level,test_release,test_preview,
                records.join(test_excludes),records.join(test_arguments)]))
if templates:
    print(sep.join(['noncompiled',normalize(templates)]))
PY

  observed_compile_context=''
  while IFS=$'\x1f' read -r kind source_dir output_dir source_level target_level release preview excludes_field args_field; do
    [[ -n "$source_dir" ]] || continue
    if [[ "$kind" == noncompiled ]]; then
      case "$source_dir" in
        "$container_source"/*) relative_dir="${source_dir#$container_source/}" ;;
        "$source_root"/*) relative_dir="${source_dir#$source_root/}" ;;
        *) continue ;;
      esac
      host_dir="$source_root/$relative_dir"
      [[ -d "$host_dir" ]] || continue
      while IFS= read -r -d '' template_file; do
        printf 'noncompiled\t%s\n' "${template_file#$source_root/}" >> "$output_file"
      done < <(find "$host_dir" -type f -name '*.java' -print0 | sort -z)
      continue
    fi
    case "$source_dir" in
      "$container_source"/*) relative_source="${source_dir#$container_source/}" ;;
      "$source_root"/*) relative_source="${source_dir#$source_root/}" ;;
      *) continue ;;
    esac
    host_source="$source_root/$relative_source"
    [[ -d "$host_source" ]] || continue
    find "$host_source" -type f -name '*.java' -print -quit | grep -q . || continue
    context_id="maven|$module_key|$kind"
    resolution_role=classpath
    # A context that compiles a module descriptor needs its dependency archives
    # on the module path: a classpath archive stays in the unnamed module and
    # cannot satisfy the descriptor's requires clauses. A descriptor the
    # compiler execution excludes is not compiled, so such a context stays
    # non-modular and keeps its dependencies on the classpath.
    if [[ -f "$host_source/module-info.java" ]]; then
      descriptor_compiled=1
      if [[ -n "$excludes_field" ]]; then
        IFS=$'\x1e' read -r -a descriptor_patterns <<< "$excludes_field"
        for pattern in "${descriptor_patterns[@]}"; do
          [[ -n "$pattern" ]] || continue
          if [[ "module-info.java" == $pattern ]]; then descriptor_compiled=0; break; fi
        done
      fi
      [[ $descriptor_compiled -eq 1 ]] && resolution_role=module-path
    fi
    printf 'context\t%s\t%s\t%s\t%s\t%s\t%s\t\n' \
      "$context_id" "$module_key" "$source_level" "$target_level" "$release" "$preview" >> "$output_file"
    printf 'source\t%s\t%s\n' "$context_id" "$relative_source" >> "$output_file"
    # MavenProject.getTestClasspathElements includes this module's production
    # output before dependency artifacts. dependency:build-classpath reports only
    # dependency artifacts, so preserve the observed production context explicitly.
    # No edge is invented when the effective model produced no Java compile context.
    if [[ "$kind" == compile ]]; then
      observed_compile_context="$context_id"
    elif [[ "$kind" == test && -n "$observed_compile_context" ]]; then
      printf 'upstream\t%s\t%s\n' "$context_id" "$observed_compile_context" >> "$output_file"
    fi
    if [[ -n "$output_dir" ]]; then
      case "$output_dir" in
        "$container_source"/*) host_output="$source_root/${output_dir#$container_source/}" ;;
        "$source_root"/*) host_output="$output_dir" ;;
        *) host_output='' ;;
      esac
      [[ -z "$host_output" ]] || printf 'output\t%s\t%s\n' "$context_id" "$host_output" >> "$output_file"
    fi
    # Provider-declared compiler arguments are build facts of the execution and
    # are replayed verbatim by the compilation-context reconstruction.
    if [[ -n "$args_field" ]]; then
      IFS=$'\x1e' read -r -a compiler_arguments <<< "$args_field"
      for compiler_argument in "${compiler_arguments[@]}"; do
        [[ -n "$compiler_argument" ]] || continue
        printf 'compiler-arg\t%s\t%s\n' "$context_id" "$compiler_argument" >> "$output_file"
      done
    fi
    # Declared compiler exclusions are build facts: the execution does not
    # consume these files even though they live under its source root. They
    # are matched against the observed source tree so every row is file-exact.
    if [[ -n "$excludes_field" ]]; then
      IFS=$'\x1e' read -r -a exclude_patterns <<< "$excludes_field"
      while IFS= read -r -d '' excluded_file; do
        relative_file="${excluded_file#$host_source/}"
        for pattern in "${exclude_patterns[@]}"; do
          [[ -n "$pattern" ]] || continue
          if [[ "$relative_file" == $pattern ]]; then
            printf 'exclude\t%s\t%s\n' "$context_id" "$relative_source/$relative_file" >> "$output_file"
            break
          fi
        done
      done < <(find "$host_source" -type f -name '*.java' -print0 | sort -z)
    fi

    cpfile="$resolution_root/result/classpath-$index-$kind.txt"
    container_cp="$container_out/result/classpath-$index-$kind.txt"
    scope=compile; [[ "$kind" == test ]] && scope=test
    docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
      --tmpfs /tmp:rw,nosuid,nodev,noexec \
      --user "$(id -u):$(id -g)" \
      --mount "type=bind,src=$source_root,dst=$container_source,readonly" \
      --mount "type=bind,src=$resolution_root,dst=$container_out" \
      -e HOME="$container_home" -e MAVEN_CONFIG="$container_maven_config" \
      -e MAVEN_OPTS="$maven_runtime_opts" -w "$container_source" "$resolver_image" \
      mvn --batch-mode --no-transfer-progress -q -f "$container_pom" \
        -Dmaven.repo.local="$container_out/repository" -DincludeScope="$scope" \
        -Dmdep.outputFile="$container_cp" "$dependency_goal"
    [[ -f "$cpfile" ]] || { echo "Maven classpath unavailable for $context_id" >&2; exit 70; }
    classpath="$(tr -d '\r\n' < "$cpfile")"
    [[ -n "$classpath" ]] || continue
    IFS=: read -r -a entries <<< "$classpath"
    declare -A seen=()
    for entry in "${entries[@]}"; do
      [[ -n "$entry" ]] || continue
      case "$entry" in
        "$container_out"/*) host="$resolution_root/${entry#$container_out/}" ;;
        "$container_source"/*) host="$source_root/${entry#$container_source/}" ;;
        *) echo "resolved Maven classpath escaped isolated mounts: $entry" >&2; exit 70 ;;
      esac
      real="$(realpath -m -- "$host")"
      [[ -n "${seen[$real]+x}" ]] && continue
      seen[$real]=1
      printf '%s\t%s\t%s\n' "$resolution_role" "$context_id" "$real" >> "$output_file"
    done
  done < "$metadata"

  # Facts 3: declared generated sources of a module whose compile context was
  # observed are materialized so that context can compile types that do not
  # exist in the checkout. Only provider-declared generator executions are run,
  # and each is run twice to prove the output is byte-stable; undeclared or
  # unstable output keeps failing closed. The declared outputs live under the
  # module build directory, which is why this step mounts the source writable.
  if [[ -n "$observed_compile_context" && -s "$generators_file" ]]; then
    while IFS=$'\t' read -r generator_type generator_execution generator_goal generator_version \
        generator_input generator_output generator_builddir; do
      [[ -n "$generator_output" && -n "$generator_version" && -n "$generator_builddir" ]] || continue
      case "$generator_type" in
        templating) generator_plugin='org.codehaus.mojo:templating-maven-plugin' ;;
        replacer) generator_plugin='com.google.code.maven-replacer-plugin:replacer' ;;
        *) continue ;;
      esac
      case "$generator_output" in
        "$container_source"/*) generated_relative="${generator_output#$container_source/}" ;;
        "$source_root"/*) generated_relative="${generator_output#$source_root/}" ;;
        *) echo "declared generated output escapes source root: $generator_output" >&2; exit 70 ;;
      esac
      case "$generator_builddir" in
        "$container_source"/*) build_relative="${generator_builddir#$container_source/}" ;;
        "$source_root"/*) build_relative="${generator_builddir#$source_root/}" ;;
        *) echo "declared build directory escapes source root: $generator_builddir" >&2; exit 70 ;;
      esac
      # Never delete anything a checkout could own: only declared build output.
      case "$generated_relative" in
        "$build_relative"/*) ;;
        *) echo "declared generated output is outside the module build directory: $generator_output" >&2; continue ;;
      esac
      host_generated="$source_root/$generated_relative"
      run_generator() {
        docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
          --tmpfs /tmp:rw,nosuid,nodev,noexec \
          --user "$(id -u):$(id -g)" \
          --mount "type=bind,src=$source_root,dst=$container_source" \
          --mount "type=bind,src=$resolution_root,dst=$container_out" \
          -e HOME="$container_home" -e MAVEN_CONFIG="$container_maven_config" \
          -e MAVEN_OPTS="$maven_runtime_opts" -w "$container_source" "$resolver_image" \
          mvn --batch-mode --no-transfer-progress -q -f "$container_pom" \
            -Dmaven.repo.local="$container_out/repository" \
            "${generator_plugin}:${generator_version}:${generator_goal}@${generator_execution}"
      }
      rm -rf -- "$host_generated"
      if ! run_generator >> "$resolution_root/generation.log" 2>&1; then
        echo "generated-source execution failed: $generator_execution ($module_key)" >&2
        continue
      fi
      if [[ ! -e "$host_generated" ]]; then
        echo "generated-source execution produced no declared output: $generator_execution ($module_key)" >&2
        continue
      fi
      first_generation="$(generated_hash "$host_generated")"
      rm -rf -- "$host_generated"
      if ! run_generator >> "$resolution_root/generation.log" 2>&1; then
        echo "generated-source repeat execution failed: $generator_execution ($module_key)" >&2
        continue
      fi
      second_generation="$(generated_hash "$host_generated")"
      if [[ -z "$first_generation" || "$first_generation" != "$second_generation" ]]; then
        echo "generated-source output is not deterministic: $generator_execution ($module_key)" >&2
        rm -rf -- "$host_generated"
        continue
      fi
      printf 'generated-source\t%s\t%s\n' "$observed_compile_context" "$generated_relative" >> "$output_file"
    done < "$generators_file"
  fi
done

# Convert classpath or module-path references to another observed context's
# output into explicit upstream edges.
python3 - "$output_file" <<'PY'
import sys
p=sys.argv[1]
rows=[line.rstrip('\n').split('\t') for line in open(p,encoding='utf-8') if line.strip()]
outputs={r[2]:r[1] for r in rows if r[0]=='output' and len(r)==3}
seen=set(); out=[]
for r in rows:
    if r[0] in ('classpath', 'module-path') and len(r)==3 and r[2] in outputs and outputs[r[2]]!=r[1]:
        r=['upstream',r[1],outputs[r[2]]]
    key=tuple(r)
    if key not in seen:
        seen.add(key); out.append(r)
with open(p,'w',encoding='utf-8') as f:
    for r in out: f.write('\t'.join(r)+'\n')
PY

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
"$script_dir/validate-java-dependency-manifest.sh" "$output_file"

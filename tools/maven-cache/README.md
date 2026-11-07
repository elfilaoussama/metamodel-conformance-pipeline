# Opt-in Maven artifact cache

This provider profile reuses dependency and build-tool artifacts through Maven's
enhanced local repository manager. It does not cache effective models, dependency
manifests, generated source trees, observations or Alloy decisions.

Supported profile: Maven **3.9.16**, Resolver **1.9.27**, Linux **JDK17**.
The Maven image must be selected by immutable digest. Other runtime versions fail
before this component can be used. The factory extends Resolver's documented
prefix-composer SPI; it does not parse or copy local repository layout/tracking.

Prepare a new cache directory once, on native Linux storage:

~~~bash
python3 -B tools/maven-cache/cache-profile.py prepare \
  /absolute/path/to/new-cache "$MAVEN_RESOLVER_IMAGE"
~~~

Use it explicitly with the normal resolver:

~~~bash
MAVEN_RESOLVER_CACHE_PROFILE=/absolute/path/to/new-cache \
  tools/resolve-maven-dependencies.sh /absolute/source /absolute/manifest.tsv
~~~

The image and normal dependency goal remain explicit provider inputs. Without
MAVEN_RESOLVER_CACHE_PROFILE, the original private, fresh repository behavior
is retained.

The prepared profile records image identity, source identity and extension hash.
The resolver checks them and a fresh component-selection receipt for every Maven
invocation; it never falls back to the default cache manager silently.

Cached remote artifact and metadata prefixes include repository ID, URL and
content type. Locally installed artifacts are scoped to the resolver attempt.
File origins are also scoped to that attempt, because identical container URLs
can refer to different mounted checkouts. Normal Maven snapshot/metadata update
policy and enhanced-manager tracking remain active. Named file locks use
Resolver's supported file-lock / file-gav configuration.

Keep the profile repository available while its returned dependency manifest is
consumed. It is distinct from the disposable per-resolution HOME, effective
model, classpath and generator state; overlapping paths are rejected before
disposable state is removed.

Validation covers sequential small builds, actual CLI injection, independent
compiler/JDK facts, complete pipeline artifacts and capsule replay, repository
origin separation, installed-output isolation, and changed mounted file origins.
Concurrent container workloads and additional Maven/Resolver profiles have not
been certified by these controls.

Primary references:

- [Maven core extensions](https://maven.apache.org/guides/mini/guide-using-extensions.html)
- [Local repository API boundaries](https://maven.apache.org/repositories/local.html)
- [Enhanced and split local repositories](https://maven.apache.org/resolver/local-repository.html)
- [Resolver 1.x configuration](https://maven.apache.org/resolver-1.x/configuration.html)
- [Resolver 1.x named locking](https://maven.apache.org/resolver-1.x/maven-resolver-impl/synccontextfactory.html)

The earlier manual artifact-copy seed under tools/performance/cache-seed-diagnostic
remains a rejected diagnostic. This profile does not use it.

// Executes the actual manifest-emission closure from the generated Gradle init script.
// No Docker or Gradle distribution is required; project configuration is supplied as facts.
import java.nio.file.Files
class TaskHarness {
    void configure(Closure action) { action.delegate = this; action() }
    void doLast(Closure action) { action() }
}
assert args.length == 1 : 'usage: groovy test-gradle-emission-local.groovy repository-root'
def script = new File(args[0], 'scripts/resolve-gradle-dependencies.sh').text
def init = script.split("<<'GRADLE'\\n", 2)[1].split('\\nGRADLE\\n', 2)[0]
def start = init.indexOf('def output = new File(outputPath)')
assert start >= 0
def emission = init.substring(start).trim()
assert emission.endsWith('}')
emission = emission.substring(0, emission.length() - 1).trim() // outer projectsEvaluated closure
emission = emission.substring(0, emission.length() - 1).trim() // observationTask.configure closure
emission = emission.substring(0, emission.length() - 1).trim() // doLast closure
def temp = Files.createTempDirectory('gradle-emission-').toFile()
def failures = []
def context = { id, project, set, outputs, entries, artifacts ->
    [id:id, module:project, roots:['code/' + id], source:'17', target:'17', release:'', preview:false,
     platform:'', entries:entries, outputs:outputs]
}
def check = { name, List contexts, List buildRoots, Closure assertion ->
    def out = new File(temp, name + '.tsv')
    def binding = new Binding([contexts:contexts, projectBuildRoots:buildRoots,
        observationTask:new TaskHarness(), outputPath:out.path])
    def shell = new GroovyShell(binding)
    try {
        boolean rejected = false
        try {
            shell.evaluate("class SourceSet { static final MAIN_SOURCE_SET_NAME = 'main' }\n" + emission)
        } catch (IllegalStateException failure) {
            if (!name.startsWith('ambiguous-')) throw failure
            assert failure.message.contains('Ambiguous compilation-context output ownership')
            rejected = true
        }
        if (name.startsWith('ambiguous-')) {
            assert rejected : 'shared output was assigned to an arbitrary context'
            println 'PASS ' + name
            return
        }
        assertion(out.text)
        if (name == 'missing-ownerless-archive' || name == 'custom-archive-not-main') {
            def validator = new ProcessBuilder('bash',
                new File(args[0], 'scripts/validate-java-dependency-manifest.sh').absolutePath,
                out.absolutePath).redirectErrorStream(true).start()
            def validationLog = validator.inputStream.text
            assert validator.waitFor() != 0 : 'unresolved dependency was accepted'
            assert validationLog.contains('dependency archive is not a regular file')
        }
        println "PASS " + name
    } catch (Throwable failure) {
        failures << name
        println "FAIL " + name + ': ' + failure.message
    }
}
try {
    def mainDir = new File(temp, 'producer/build/classes/main')
    def customDir = new File(temp, 'producer/build/classes/special')
    def buildRoot = new File(temp, 'producer/build').toPath()
    def archive = new File(temp, 'producer/build/libs/special.jar')
    def producer = context('producer', ':producer', 'main', [mainDir], [], [:])
    def custom = context('special', ':producer', 'special', [customDir], [], [:])
    def consumer = { entries, artifacts -> context('consumer', ':consumer', 'main', [], entries, artifacts) }
    def roots = [[projectPath:':producer', path:buildRoot]]
    check('exact-output', [producer, consumer([mainDir], [:])], roots) { text ->
        assert text.contains('upstream\tconsumer\tproducer\n')
    }
    check('custom-output', [producer, custom, consumer([customDir], [:])], roots) { text ->
        assert text.contains('upstream\tconsumer\tspecial\n')
    }
    def conflicting = context('conflicting', ':producer', 'variant', [mainDir], [], [:])
    check('ambiguous-output', [producer, conflicting, consumer([mainDir], [:])], roots) { text -> }
    check('ambiguous-reversed', [conflicting, producer, consumer([mainDir], [:])], roots) { text -> }
    check('missing-ownerless-archive',
          [consumer([archive], [(archive.path):':producer'])], roots) { text ->
        assert text.contains('classpath\tconsumer\t' + archive.path + '\n') :
            'missing dependency was silently discarded'
    }
    check('custom-archive-not-main', [producer, custom, consumer([archive], [(archive.path):':producer'])], roots) { text ->
        assert text.contains('classpath\tconsumer\t' + archive.path + '\n') :
            'project identity does not establish source-set variant'
        assert !text.contains('upstream\tconsumer\tproducer\n')
    }
    archive.parentFile.mkdirs(); archive.text = 'existing archive evidence'
    check('existing-archive-preserved', [producer, consumer([archive], [:])], roots) { text ->
        assert text.contains('classpath\tconsumer\t' + archive.path + '\n') :
            'build-directory containment is not source-set ownership'
    }
    def external = new File(temp, 'external.jar'); external.text = 'external'
    [[external, mainDir], [mainDir, external]].eachWithIndex { ordered, index ->
        check('upstream-path-order-' + index, [producer, consumer(ordered, [:])], roots) { text ->
            def actual = text.readLines().findAll { it.startsWith('classpath\tconsumer\t') }
                    .collect { it.split('\t', -1)[2] }
            assert actual == ordered.collect { it.path } : 'upstream output lost its original path position'
            assert text.contains('upstream\tconsumer\tproducer\n')
        }
    }
    check('external-archive', [producer, consumer([external], [:])], roots) { text ->
        assert text.contains('classpath\tconsumer\t' + external.path + '\n')
    }
    check('own-output', [context('producer', ':producer', 'main', [mainDir], [mainDir], [:])], roots) { text ->
        assert text.contains('classpath\tproducer\t' + mainDir.path + '\n')
    }
} finally {
    temp.deleteDir()
}
assert failures.isEmpty() : "Emission regressions: " + failures
println 'GRADLE_EMISSION_BEHAVIOR_OK'

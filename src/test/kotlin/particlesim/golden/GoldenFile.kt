package particlesim.golden

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Golden-file regression harness (§15.2): samples a compact, named-particle subset of state
 * at a few points in time into a plain, diffable text format — deliberately not the
 * production Arrow IPC recording (§9.2), which is free to evolve independently of what a
 * golden test needs to stay stable.
 */
object GoldenFile {

    /** One sampled particle at one point in time. */
    data class Sample(val t: Double, val label: String, val position: Vector3, val velocity: Vector3)

    /** Renders samples into the checked-in text format: one line per sample, fixed precision
     * so the output is stable across runs on unchanged code and still readable in a diff. */
    fun render(samples: List<Sample>): String =
        samples.joinToString("\n", postfix = "\n") { s ->
            "t=${fmt(s.t)} ${s.label} pos=(${fmt(s.position.x)},${fmt(s.position.y)},${fmt(s.position.z)})" +
                " vel=(${fmt(s.velocity.x)},${fmt(s.velocity.y)},${fmt(s.velocity.z)})"
        }

    // Fixed-decimal, not scale-independent: a value near 1e-12 renders as 0.000000000 (a real
    // change there wouldn't move this file), and a value near 1e9 loses its fractional digits
    // entirely. Fine for scenarios with values in a moderate range (like the N-body one this
    // started with); switch to "%.12e" if a future scenario has particles at very different
    // scales (e.g. a flag's small per-vertex displacements) and needs this to actually notice.
    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.9f", v)

    /**
     * Asserts [samples] renders identically to the checked-in reference at
     * `src/test/resources/golden/<name>.golden.txt`. A failure means either a real bug or an
     * intentional behavior change — regenerate deliberately with [regenerate], never
     * automatically as a side effect of running tests.
     *
     * Reads the reference as a classpath resource, not a source-tree-relative file: the JVM
     * working directory a test runs from isn't guaranteed (an IDE run configuration commonly
     * differs from `./gradlew test`'s project-root CWD), and a golden test that can silently
     * fail to find its own reference stops being able to catch a real regression.
     */
    fun assertMatchesReference(name: String, samples: List<Sample>) {
        val actual = render(samples)
        val resource = javaClass.getResourceAsStream("/golden/$name.golden.txt")
            ?: throw AssertionError(
                "no golden reference for '$name' on the test classpath (expected " +
                    "src/test/resources/golden/$name.golden.txt) — this is a test failure, not a " +
                    "prompt to regenerate: call GoldenFile.regenerate(\"$name\", samples) explicitly, " +
                    "review the diff, and check the result in",
            )
        val expected = resource.bufferedReader().use { it.readText() }
        assertEquals(expected, actual, "golden file '$name' mismatch — real bug or reviewed, intentional regeneration?")
    }

    /** Deliberately (re)writes the checked-in reference. Never called from a normal test run —
     * invoke explicitly (e.g. from a one-off `main`) and review the resulting diff before committing. */
    fun regenerate(name: String, samples: List<Sample>) {
        referenceFile(name).apply { parentFile.mkdirs() }.writeText(render(samples))
    }

    private fun referenceFile(name: String): File =
        File("src/test/resources/golden/$name.golden.txt")
}

/** Convenience: sample a set of (label, id) particles from [store] at time [t]. */
fun sampleParticles(store: ParticleStore, t: Double, labeled: List<Pair<String, Int>>): List<GoldenFile.Sample> =
    labeled.map { (label, id) -> GoldenFile.Sample(t, label, store.position(id), store.velocity(id)) }

package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel

/**
 * The bundle shipped before the realistic-naps experiment (docs/prereg/prereg-naps-realistic.md):
 * `pulsestages-walch+bidsleep-mlp-nap-model-final`, kept byte for byte as the test resource [RESOURCE] ("the previous
 * bundle" of the pre-registration, sections 15 and 16), with the fingerprints that the build of the base commit
 * `cad7a6f` computed for it BEFORE any code change of the experiment. They were computed with the saved base build
 * (tools/train/out/naps-realistic-base/algo-cli, a JVM probe calling `HybridModel.fingerprint()` and
 * `nightModelFingerprint()` on its jars) and logged in tools/train/out/commands.log (copied to
 * docs/prereg/commands-naps-realistic.log) as the NOTE "naps-realistic base-build fingerprints" of
 * 2026-10-15T04:09:28+02:00. They are copied from that NOTE, never printed by a modified build.
 */
internal object PreviousBundle {
    /** Test resource: a byte-identical copy of the previous `app/src/main/assets/models/hybrid_model.json`. */
    const val RESOURCE = "nap-model-final-bundle.json"

    /** SHA-256 of [RESOURCE]. */
    const val SHA256 = "583df315d12af09bd1a0e72bd78d1e1d835d6e8ae53565a264368538591530f9"

    /** `fingerprint()` of [RESOURCE] as computed by the base build (logged NOTE). */
    const val FINGERPRINT = "6589c5fa749cfe1a7563a990cd6cd0152e3bfe1bb607528c85d11baae8112a69"

    /** `nightModelFingerprint()` of [RESOURCE] as computed by the base build (logged NOTE). */
    const val NIGHT_MODEL_FINGERPRINT = "50c145ef2d0f1e5b5b9d14abbf041da3a88c3f507da04ba41d05a7c46006b9cf"

    /** The raw bytes of [RESOURCE]. */
    val bytes: ByteArray by lazy { checkNotNull(PreviousBundle::class.java.getResource("/$RESOURCE")) { "missing $RESOURCE" }.readBytes() }

    /** [RESOURCE] parsed. */
    val model: HybridModel by lazy { HybridModel.parse(bytes.toString(Charsets.UTF_8)) }
}

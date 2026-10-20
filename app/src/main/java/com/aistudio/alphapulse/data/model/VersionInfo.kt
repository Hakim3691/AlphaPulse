/***
 * VersionInfo - Model version information linked to predictions for scientific reproducibility.
 * Every prediction shall be linked to: Quant Engine Version, TSFM Version,
 * LOB Model Version, Decision Router Version, SLM Version, Feature Schema Version.
 * See §55 and ModelManager for integration.
 *
 * Spec Ref: §55
 */
data class VersionInfo(
    val quantEngineVersion: String = "1.0",
    val tsfmVersion: String = "1.0",
    val lobModelVersion: String = "1.0",
    val decisionRouterVersion: String = "1.0",
    val slmVersion: String = "1.0",
    val featureSchemaVersion: String = "1.0"
)
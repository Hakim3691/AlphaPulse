package com.aistudio.alphapulse.domain.ensemble

import com.aistudio.alphapulse.domain.quant.ModelState
import kotlin.math.sqrt

/**
 * 30-day per-coin calibration of the ensemble (spec §20 Adaptive Ensemble
 * Learning, §31 Model Ensemble Health).
 *
 * Why it exists
 * -------------
 * The online AdamW step learns one prediction at a time, which is slow and
 * reacts late to a coin whose volatility profile is nothing like the last one.
 * This calibration answers a different, coarser question before trading starts:
 * *over the last 30 days of this specific asset, which mounted TSFM actually
 * predicted its horizon-sized moves better?* The answer becomes the W4/W5 split
 * for that coin and horizon, persisted so a restart does not forget it.
 *
 * What is measured
 * ----------------
 * A rolling-context evaluation: for each origin inside the 30-day window the
 * model is given every close up to that origin and asked for the next close.
 * The error against what really happened gives MAE and RMSE, and the sign of the
 * prediction against the sign of the move gives directional accuracy. Nothing is
 * trained: the models are used exactly as shipped, which is what makes the score
 * comparable across runs.
 *
 * Weighting
 * ---------
 * The TSFM budget is the same one the dock already grants on mount
 * ([EnsembleWeighting.MOUNTED_MEMBER_WEIGHT] per model), split by inverse MAE.
 * The core tri-factor keeps the remaining budget, so ΣW = 1.000 still holds and
 * a 30-day window can never hand the ensemble to a single model.
 *
 * Spec Ref: §20 Adaptive Ensemble Learning, §23 Forecast Scheduling,
 * §31 Model Ensemble Health
 */

/** One supported forecast horizon and the exchange interval used to score it. */
data class CalibrationHorizon(
    val id: String,
    val minutes: Int,
    val interval: String
)

/** The horizons the engine forecasts, in the same order the UI shows them. */
object CalibrationHorizons {

    val ALL: List<CalibrationHorizon> = listOf(
        CalibrationHorizon("1m", 1, "1m"),
        CalibrationHorizon("5m", 5, "5m"),
        CalibrationHorizon("15m", 15, "15m"),
        CalibrationHorizon("30m", 30, "30m"),
        CalibrationHorizon("1h", 60, "1h")
    )

    /** The short-end horizons a scalp desk calibrates together. */
    val INTRADAY: List<CalibrationHorizon> = ALL.filter { it.minutes <= 15 }

    /** The swing horizons that follow the reference horizon upward. */
    val SWING: List<CalibrationHorizon> = ALL.filter { it.minutes >= 15 }

    /** The horizon whose ensemble legs the §29 router scores. */
    val REFERENCE: List<CalibrationHorizon> = ALL.filter { it.id == "15m" }

    fun byId(id: String): CalibrationHorizon? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
}

/** Which component chose the calibration plan. */
enum class CalibrationPlanSource {
    /** Cactus Needle 3 picked the horizon set on device (§30). */
    NEEDLE3,

    /** The deterministic plan ran because the engine could not. */
    DETERMINISTIC
}

/**
 * What to calibrate for one asset.
 *
 * @property symbol normalised market symbol, e.g. `BTCUSDT`
 * @property windowDays days of history requested (30 unless overridden)
 * @property horizons horizons to score
 * @property modelIds mounted TSFM ids, in canonical W4/W5 order; empty means
 *   nothing can be scored and no work should be done
 * @property dispatchedBy who chose this plan
 * @property reason the dispatcher's own sentence, kept for the audit trail
 */
data class CoinCalibrationPlan(
    val symbol: String,
    val windowDays: Int,
    val horizons: List<CalibrationHorizon>,
    val modelIds: List<String>,
    val dispatchedBy: CalibrationPlanSource,
    val reason: String
)

/**
 * How well one model predicted one horizon of one coin over the window.
 *
 * @property mae mean absolute error in price units
 * @property rmse root mean squared error in price units
 * @property directionalAccuracy share of origins where the sign of the move was
 *   predicted correctly, 0..1
 * @property samples origins actually scored (a model can be absent for some)
 */
data class ModelHorizonScore(
    val modelId: String,
    val horizonId: String,
    val mae: Double,
    val rmse: Double,
    val directionalAccuracy: Double,
    val samples: Int
)

/** The calibrated weights and the evidence behind them for one horizon. */
data class HorizonCalibration(
    val horizonId: String,
    val minutes: Int,
    val weights: ModelState,
    val scores: List<ModelHorizonScore>,
    val samples: Int
)

/**
 * Outcome of one 30-day calibration run.
 *
 * @property barsPerHorizon how many bars were actually available per horizon;
 *   a window truncated by the exchange page cap is visible here instead of being
 *   silently reported as 30 days
 */
data class CoinCalibrationReport(
    val symbol: String,
    val generatedAt: Long,
    val windowDays: Int,
    val dispatchedBy: CalibrationPlanSource,
    val planReason: String,
    val horizons: List<HorizonCalibration>,
    val barsPerHorizon: Map<String, Int>
) {
    /** Calibrated weights for one horizon, or null when it was not evaluated. */
    fun weightsFor(horizonId: String): ModelState? =
        horizons.firstOrNull { it.horizonId.equals(horizonId, ignoreCase = true) }?.weights
}

/** The pure half of calibration: scoring and weight assignment. */
object EnsembleCalibration {

    /** Days of history the pipeline evaluates by default. */
    const val WINDOW_DAYS: Int = 30

    /** Origins scored per horizon; enough for a stable MAE without a 30-day scan. */
    const val MAX_EVALUATION_ORIGINS: Int = 32

    /** Bars of context a TSFM needs before its forecast is meaningful. */
    const val MIN_CONTEXT_BARS: Int = 32

    /**
     * Score one model's predictions against what the market did.
     *
     * All three lists are parallel and describe the same origins: the close the
     * model was given ([references]), the close it predicted, and the close that
     * actually printed one horizon later.
     *
     * @return null when nothing was scored, so a model is never ranked on zero
     *   evidence
     */
    fun score(
        modelId: String,
        horizonId: String,
        predictions: List<Double>,
        actuals: List<Double>,
        references: List<Double>
    ): ModelHorizonScore? {
        if (predictions.isEmpty() || predictions.size != actuals.size || actuals.size != references.size) {
            return null
        }

        var absolute = 0.0
        var squared = 0.0
        var correctDirection = 0
        var scored = 0

        for (i in predictions.indices) {
            val predicted = predictions[i]
            val actual = actuals[i]
            val reference = references[i]
            if (!predicted.isFinite() || !actual.isFinite() || !reference.isFinite()) continue

            val error = predicted - actual
            absolute += kotlin.math.abs(error)
            squared += error * error
            scored++

            // A flat prediction of a flat move is not a directional call; only a
            // real sign on both sides counts as a hit.
            val predictedSign = predicted.compareTo(reference)
            val actualSign = actual.compareTo(reference)
            if (predictedSign != 0 && predictedSign == actualSign) correctDirection++
        }

        if (scored == 0) return null

        return ModelHorizonScore(
            modelId = modelId,
            horizonId = horizonId,
            mae = absolute / scored,
            rmse = sqrt(squared / scored),
            directionalAccuracy = correctDirection.toDouble() / scored,
            samples = scored
        )
    }

    /**
     * Turn inverse error into this horizon's W4/W5 split.
     *
     * A model with no usable score keeps its equal share rather than being
     * dropped: the dock mounted it, so the ensemble must still account for it.
     * Members are only ever assigned a share they are mounted for, and the core
     * tri-factor keeps exactly the budget the mount contract leaves it.
     */
    fun weightsForHorizon(
        mountedModelIds: Collection<String>,
        scores: List<ModelHorizonScore>
    ): ModelState {
        val mounted = EnsembleWeighting.mountedExtraIds(mountedModelIds)
        val state = ModelState()
        EnsembleWeighting.rebalanceForMountedModels(state, mounted)
        if (mounted.isEmpty()) return state

        val usable = scores.filter { score ->
            score.modelId in mounted &&
                score.samples > 0 &&
                score.mae.isFinite() &&
                score.mae > 0.0
        }
        if (usable.isEmpty()) return state

        val inverse = usable.associate { it.modelId to 1.0 / it.mae }
        // A mounted model that could not be scored must not be penalised for it;
        // it receives the average inverse error of the models that could be.
        val fallback = inverse.values.average()
        val shares = mounted.associateWith { id -> inverse[id] ?: fallback }
        val total = shares.values.sum()
        if (total <= 0.0 || !total.isFinite()) return state

        val budget = (EnsembleWeighting.MOUNTED_MEMBER_WEIGHT * mounted.size).coerceAtMost(1.0)
        state.weight4 = if (EnsembleWeighting.CHRONOS_ID in mounted) {
            budget * (shares[EnsembleWeighting.CHRONOS_ID] ?: 0.0) / total
        } else {
            0.0
        }
        state.weight5 = if (EnsembleWeighting.TIMESFM_ID in mounted) {
            budget * (shares[EnsembleWeighting.TIMESFM_ID] ?: 0.0) / total
        } else {
            0.0
        }
        state.mode = CALIBRATED_MODE
        return state
    }

    /** Mode label shown next to weights that came from a 30-day calibration. */
    const val CALIBRATED_MODE: String = "30-Day Calibrated"
}

package com.example.iras.ai

class TechniqueEvaluator(
    private val windEvaluator: WindEvaluator = WindEvaluator()
) {

    companion object {
        const val DRAW_ELBOW_MIN_DEG = 20f
        const val DRAW_ELBOW_MAX_DEG = 60f
        const val ALIGNMENT_MAX_DEG = 8f
        const val STANCE_MAX_DEG = 10f
    }

    fun evaluate(
        features: BiomechanicsFeatures,
        wind: WindSnapshot?
    ): TechniqueEvaluation {
        val windEval = windEvaluator.evaluate(wind)

        if (!features.isValid) {
            // Pesan dibuat pendek supaya enak dibacakan sebagai instruksi suara.
            val issues = mutableListOf(
                FeedbackIssue("INVALID", "CRITICAL", "Bidikan tak terbaca. Geser HP ke samping.")
            )
            // Tetap sisipkan peringatan kritis angin Level 4 meski postur tidak terbaca
            if (windEval != null && windEval.isCritical && windEval.cueCode != null && windEval.ttsMessage != null) {
                issues.add(FeedbackIssue(windEval.cueCode, "CRITICAL", windEval.ttsMessage))
            }
            return TechniqueEvaluation(
                isValid = false,
                techniqueStatus = "INVALID_DATA",
                metrics = features,
                issues = issues,
                feedbackText = if (windEval?.isCritical == true && windEval.ttsMessage != null) {
                    windEval.ttsMessage
                } else {
                    "Postur tidak terlihat jelas, arahkan HP ke samping pemanah."
                },
                timestampMs = features.featureTimestampMs
            )
        }

        val issues = mutableListOf<FeedbackIssue>()
        var feedbackText = "Posisi ideal."

        features.drawArmElbowAngleDeg?.let { angle ->
            if (angle < DRAW_ELBOW_MIN_DEG) {
                issues.add(FeedbackIssue("DRAW_ELBOW_CLOSED", "WARNING", "Siku penarik terlalu tertutup."))
                if (issues.size == 1) feedbackText = "Siku penarik terlalu tertutup."
            } else if (angle > DRAW_ELBOW_MAX_DEG) {
                issues.add(FeedbackIssue("DRAW_ELBOW_OPEN", "WARNING", "Siku penarik terlalu terbuka."))
                if (issues.size == 1) feedbackText = "Siku penarik terlalu terbuka."
            }
        }

        features.bodyAlignmentDeviationDeg?.let { dev ->
            if (dev > ALIGNMENT_MAX_DEG) {
                issues.add(
                    FeedbackIssue(
                        "ALIGNMENT_BAD",
                        "WARNING",
                        "Sejajarkan bahu dan pinggul."
                    )
                )
                if (issues.size == 1) feedbackText = "Sejajarkan bahu dan pinggul Anda."
            }
        }

        features.stanceDeviationDeg?.let { dev ->
            if (dev > STANCE_MAX_DEG) {
                issues.add(FeedbackIssue("STANCE_BAD", "WARNING", "Ratakan posisi kaki."))
                if (issues.size == 1) feedbackText = "Ratakan posisi kaki."
            }
        }

        if (windEval != null && windEval.shouldSpeak && windEval.cueCode != null && windEval.ttsMessage != null) {
            val severity = if (windEval.isCritical) "CRITICAL" else "WARNING"
            issues.add(FeedbackIssue(windEval.cueCode, severity, windEval.ttsMessage))
            feedbackText = if (windEval.isCritical) {
                windEval.ttsMessage
            } else if (issues.size == 1) {
                windEval.ttsMessage
            } else {
                "$feedbackText ${windEval.ttsMessage}"
            }
        }

        val status = if (issues.isEmpty()) "IDEAL" else "NEEDS_CORRECTION"

        return TechniqueEvaluation(
            isValid = true,
            techniqueStatus = status,
            metrics = features,
            issues = issues,
            feedbackText = feedbackText,
            timestampMs = features.featureTimestampMs
        )
    }
}
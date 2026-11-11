package com.example.iras.ai

class TechniqueEvaluator {

    fun evaluate(
        features: BiomechanicsFeatures,
        wind: WindSnapshot?
    ): TechniqueEvaluation {
        if (!features.isValid) {
            return TechniqueEvaluation(
                isValid = false,
                techniqueStatus = "INVALID_DATA",
                metrics = features,
                issues = listOf(FeedbackIssue("INVALID", "CRITICAL", features.invalidReasons.joinToString())),
                feedbackText = "Postur tidak terlihat jelas, sesuaikan posisi kamera.",
                timestampMs = features.featureTimestampMs
            )
        }

        val issues = mutableListOf<FeedbackIssue>()
        var feedbackText = "Posisi ideal."

        if (features.drawArmElbowAngleDeg < 155f) {
            issues.add(FeedbackIssue("DRAW_ARM_LOW", "WARNING", "Sudut siku penarik kurang dari 155 derajat."))
            feedbackText = "Siku lengan penarik kurang naik."
        } else if (features.drawArmElbowAngleDeg > 175f) {
            issues.add(FeedbackIssue("DRAW_ARM_HIGH", "WARNING", "Sudut siku penarik lebih dari 175 derajat."))
            feedbackText = "Siku lengan penarik terlalu lurus."
        }

        if (features.bodyAlignmentDeviationDeg > 9f) {
            issues.add(FeedbackIssue("ALIGNMENT_BAD", "WARNING", "Deviasi bahu-pinggul melebihi 9 derajat."))
            if (issues.size == 1) feedbackText = "Sejajarkan bahu dan pinggul Anda."
        }

        if (wind != null && wind.isValid) {
            if (wind.speedMps > 5.0f) {
                issues.add(FeedbackIssue("WIND_COMPENSATION", "WARNING", "Angin kencang ${wind.speedMps} m/s."))
                feedbackText += " Angin kencang, sesuaikan arah bidikan."
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
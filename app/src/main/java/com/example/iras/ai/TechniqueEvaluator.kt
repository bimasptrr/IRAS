package com.example.iras.ai

class TechniqueEvaluator {

    companion object {

        const val DRAW_ELBOW_MIN_DEG = 20f
        const val DRAW_ELBOW_MAX_DEG = 60f
        const val DRAW_ELBOW_FACE_MIN = -0.15f
        const val DRAW_ELBOW_FACE_MAX = 1.15f
        const val ANCHOR_FACE_MIN = 0.3f
        const val ANCHOR_FACE_MAX = 1.6f
        const val BOW_ELBOW_MIN_DEG = 160f
        const val BOW_ELBOW_MAX_DEG = 178f
        const val ALIGNMENT_MAX_DEG = 8f
        const val STANCE_MAX_DEG = 10f
    }

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
                feedbackText = "Postur tidak terlihat jelas, arahkan HP ke samping pemanah.",
                timestampMs = features.featureTimestampMs
            )
        }

        val issues = mutableListOf<FeedbackIssue>()
        var feedbackText = "Posisi ideal."

        features.drawElbowFaceRatio?.let { ratio ->
            if (ratio < DRAW_ELBOW_FACE_MIN) {
                issues.add(FeedbackIssue("DRAW_ELBOW_TOO_HIGH", "WARNING", "Siku lengan penarik terlalu tinggi."))
                feedbackText = "Siku lengan penarik terlalu tinggi, turunkan sedikit."
            } else if (ratio > DRAW_ELBOW_FACE_MAX) {
                issues.add(FeedbackIssue("DRAW_ELBOW_TOO_LOW", "WARNING", "Siku lengan penarik kurang naik."))
                feedbackText = "Siku lengan penarik kurang naik, naikkan sampai bawah dagu."
            }
        }

        features.drawArmElbowAngleDeg?.let { angle ->
            if (angle < DRAW_ELBOW_MIN_DEG) {
                issues.add(FeedbackIssue("DRAW_ELBOW_CLOSED", "WARNING", "Sudut siku penarik terlalu tertutup."))
                if (issues.size == 1) feedbackText = "Sudut siku lengan penarik terlalu tertutup."
            } else if (angle > DRAW_ELBOW_MAX_DEG) {
                issues.add(FeedbackIssue("DRAW_ELBOW_OPEN", "WARNING", "Sudut siku penarik terlalu terbuka."))
                if (issues.size == 1) feedbackText = "Sudut siku lengan penarik terlalu terbuka."
            }
        }

        features.drawHandFaceRatio?.let { ratio ->
            if (ratio < ANCHOR_FACE_MIN || ratio > ANCHOR_FACE_MAX) {
                issues.add(
                    FeedbackIssue(
                        "ANCHOR_OFF",
                        "WARNING",
                        "Tangan penarik di luar area rahang (posisi ${"%.2f".format(ratio)})."
                    )
                )
                if (issues.size == 1) feedbackText = "Tangan penarik belum tepat di rahang."
            }
        }

        features.bowArmElbowAngleDeg?.let { angle ->
            if (angle < BOW_ELBOW_MIN_DEG) {
                issues.add(FeedbackIssue("BOW_ELBOW_BENT", "WARNING", "Lengan busur belum lurus."))
                if (issues.size == 1) feedbackText = "Lengan busur luruskan."
            } else if (angle > BOW_ELBOW_MAX_DEG) {
                issues.add(FeedbackIssue("BOW_ELBOW_LOCKED", "INFO", "Lengan busur terkunci lurus."))
            }
        }

        features.bodyAlignmentDeviationDeg?.let { dev ->
            if (dev > ALIGNMENT_MAX_DEG) {
                issues.add(
                    FeedbackIssue(
                        "ALIGNMENT_BAD",
                        "WARNING",
                        "Deviasi bahu-pinggul melebihi ${ALIGNMENT_MAX_DEG.toInt()} derajat."
                    )
                )
                if (issues.size == 1) feedbackText = "Sejajarkan bahu dan pinggul Anda."
            }
        }

        features.stanceDeviationDeg?.let { dev ->
            if (dev > STANCE_MAX_DEG) {
                issues.add(FeedbackIssue("STANCE_BAD", "WARNING", "Posisi kaki tidak sejajar dengan pinggul."))
                if (issues.size == 1) feedbackText = "Ratakan posisi kaki."
            }
        }

        if (wind != null && wind.isValid && wind.speedMps > 5.0f) {
            issues.add(FeedbackIssue("WIND_COMPENSATION", "WARNING", "Angin kencang ${wind.speedMps} m/s."))
            feedbackText += " Angin kencang, sesuaikan arah bidikan."
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

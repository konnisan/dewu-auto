package com.konnisan.dewuauto.automation

/**
 * “露脸”不是普通的包含即排除词：任务明确允许上身不露脸作为替代方案时可以通过。
 * 未识别出明确替代关系时采用安全侧判断，视为需要露脸。
 */
object FaceRequirementPolicy {
    private const val UPPER_BODY = "(?:上身|上半身)"
    private val alternativePatterns = listOf(
        Regex("露脸(?:照)?(?:/|／|或|或者)(?:不露脸)?(?:的)?$UPPER_BODY(?:照)?"),
        Regex("$UPPER_BODY(?:照)?(?:/|／|或|或者)(?:不露脸)?(?:的)?露脸(?:照)?"),
    )
    private val explicitChoiceMarkers = listOf("二选一", "任选其一", "任选一种", "可任选", "任选")
    private val noFaceMarkers = listOf("不露脸", "无需露脸", "不用露脸", "免露脸")

    fun evaluate(fieldName: String, rawValue: String): TaskEligibility {
        val value = rawValue.replace(Regex("\\s+"), "")
        if (!value.contains("露脸")) {
            return TaskEligibility(true, "$fieldName 未要求露脸")
        }

        val mentionsUpperBody = Regex(UPPER_BODY).containsMatchIn(value)
        val offersUpperBodyAlternative =
            alternativePatterns.any { it.containsMatchIn(value) } ||
                (mentionsUpperBody && explicitChoiceMarkers.any(value::contains)) ||
                (mentionsUpperBody && noFaceMarkers.any(value::contains))

        return if (offersUpperBodyAlternative) {
            TaskEligibility(true, "$fieldName 支持上身不露脸方案")
        } else {
            TaskEligibility(
                eligible = false,
                reason = "$fieldName 明确要求露脸",
                matchedField = fieldName,
                matchedWord = "露脸",
            )
        }
    }
}

package studio.junecity.greenhouse.cloud

import studio.junecity.greenhouse.BuildConfig

object CloudRuntime {
    fun configurationOrNull(): CognitoConfiguration? = configurationOrNull(
        BuildConfig.GREENHOUSE_AWS_REGION,
        BuildConfig.GREENHOUSE_COGNITO_USER_POOL_ID,
        BuildConfig.GREENHOUSE_COGNITO_APP_CLIENT_ID,
        BuildConfig.GREENHOUSE_AWS_API_BASE_URL
    )

    fun configurationOrNull(region: String, userPoolId: String, appClientId: String, apiBaseUrl: String): CognitoConfiguration? {
        val values = listOf(region, userPoolId, appClientId, apiBaseUrl).map(String::trim)
        if (values.all(String::isBlank)) return null
        require(values.none(String::isBlank)) { "All AWS cloud deployment outputs must be configured together" }
        return CognitoConfiguration(values[0], values[1], values[2], values[3])
    }
}

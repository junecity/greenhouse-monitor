package studio.junecity.greenhouse.cloud

import android.content.Context
import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.AuthSignInResult
import com.amplifyframework.auth.result.AuthSignUpResult
import com.amplifyframework.auth.options.AuthSignUpOptions
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.configuration.AmplifyOutputsData
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CognitoConfiguration(
    val region: String,
    val userPoolId: String,
    val appClientId: String,
    val apiBaseUrl: String
) {
    init {
        require(region.matches(Regex("[a-z]{2}(-gov)?-[a-z]+-\\d"))) { "Invalid AWS region" }
        require(userPoolId.startsWith("${region}_")) { "User Pool does not match region" }
        require(appClientId.matches(Regex("[a-z0-9]{20,128}"))) { "Invalid Cognito app client" }
        require(apiBaseUrl.startsWith("https://")) { "API Gateway URL must use HTTPS" }
    }
}

/** AWS Cognito is the sole customer identity provider. Amplify retains and refreshes tokens. */
class CognitoAuthManager(private val context: Context, private val configuration: CognitoConfiguration) : CloudTokenProvider {
    fun initialize() {
        if (configured.compareAndSet(false, true)) {
            try {
                val outputs = AmplifyOutputsData(auth = AmplifyOutputsData.Auth(
                    awsRegion = configuration.region,
                    userPoolId = configuration.userPoolId,
                    userPoolClientId = configuration.appClientId
                ))
                Amplify.addPlugin(AWSCognitoAuthPlugin())
                Amplify.configure(outputs, context.applicationContext)
            } catch (error: Exception) {
                configured.set(false)
                throw error
            }
        }
    }

    suspend fun signUp(email: String, password: String): AuthSignUpResult = callback { success, failure ->
        Amplify.Auth.signUp(email.trim(), password, AuthSignUpOptions.builder().build(), success, failure)
    }

    suspend fun confirmSignUp(email: String, code: String): AuthSignUpResult = callback { success, failure ->
        Amplify.Auth.confirmSignUp(email.trim(), code.trim(), success, failure)
    }

    suspend fun signIn(email: String, password: String): AuthSignInResult = callback { success, failure ->
        Amplify.Auth.signIn(email.trim(), password, success, failure)
    }

    suspend fun signOut() = suspendCancellableCoroutine { continuation ->
        Amplify.Auth.signOut { continuation.resume(Unit) }
    }

    override suspend fun accessToken(): String {
        val session: AWSCognitoAuthSession = callback { success, failure ->
            Amplify.Auth.fetchAuthSession({ success(it as AWSCognitoAuthSession) }, failure)
        }
        if (!session.isSignedIn) throw AuthException("Cognito session is signed out", "Sign in again")
        return session.userPoolTokensResult.value?.accessToken ?: throw AuthException("Cognito access token unavailable", "Sign in again")
    }

    fun gateway(deviceId: String) = AuthenticatedCloudGateway(CloudApiConfig(configuration.apiBaseUrl, deviceId), this)

    private suspend fun <T> callback(block: ((T) -> Unit, (AuthException) -> Unit) -> Unit): T = suspendCancellableCoroutine { continuation ->
        block({ if (continuation.isActive) continuation.resume(it) }, { if (continuation.isActive) continuation.resumeWithException(it) })
    }

    private companion object { val configured = AtomicBoolean(false) }
}

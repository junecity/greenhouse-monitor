package studio.junecity.greenhouse.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.amplifyframework.auth.AuthException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.ui.DashboardTokens
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private enum class OnboardingStep { Auth, Confirm, ResetPassword, Mode, ServerPair, ClientPair }

fun pairingCodeFromPayload(payload: String): String? {
    val value = payload.trim()
    if (value.length in 24..128 && !value.contains("://")) return value
    return runCatching {
        val uri = URI(value)
        if (uri.scheme != "greenhouse" || uri.host != "cloud-pair") null
        else uri.rawQuery.orEmpty().split('&').asSequence().mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2 && pieces[0] == "code") URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.name()) else null
        }.firstOrNull()?.takeIf { it.length in 24..128 }
    }.getOrNull()
}

private fun pairingPayload(code: String) = "greenhouse://cloud-pair?v=1&code=${URLEncoder.encode(code, StandardCharsets.UTF_8.name())}"

@Composable
fun CloudOnboardingGate(
    configuration: CognitoConfiguration?,
    configurationError: String?,
    onEnrolled: (CloudDeviceRole) -> Unit,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val secure = remember { SecureSettingsStore(context) }
    var enrollment by remember { mutableStateOf(secure.cloudEnrollment()) }
    var sessionValidated by remember { mutableStateOf(false) }
    val showMonitoring = configurationError == null &&
        (configuration == null || (enrollment != null && sessionValidated))

    AppOrientation(monitoring = showMonitoring)

    when {
        configurationError != null -> CloudSetupShell(
            "Service unavailable",
            "This build cannot connect to junecity.studio account services."
        )
        showMonitoring -> content()
        else -> CloudOnboarding(
            configuration = requireNotNull(configuration),
            secure = secure,
            existingEnrollment = enrollment,
            onAuthenticated = { sessionValidated = true },
            onComplete = {
                secure.saveCloudEnrollment(it)
                enrollment = it
                sessionValidated = true
                onEnrolled(it.role)
            }
        )
    }
}

@Composable
private fun CloudOnboarding(
    configuration: CognitoConfiguration,
    secure: SecureSettingsStore,
    existingEnrollment: CloudEnrollment?,
    onAuthenticated: () -> Unit,
    onComplete: (CloudEnrollment) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val authResult = remember(configuration) { runCatching { CognitoAuthManager(context, configuration).also(CognitoAuthManager::initialize) } }
    val auth = authResult.getOrNull()
    val deviceId = remember { secure.cloudDeviceId() }
    val greenhouseId = remember { secure.cloudGreenhouseId() }
    val gateway = remember(auth, deviceId) { auth?.gateway(deviceId) }
    var step by remember { mutableStateOf(OnboardingStep.Auth) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmationCode by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var pendingServer by remember { mutableStateOf<CloudEnrollment?>(null) }
    var challenge by remember { mutableStateOf<PairingChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(authResult.exceptionOrNull()?.let(::accountErrorMessage)) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairingCodeFromPayload(it) }?.let { pairingCode = it }
    }
    val runAction: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            busy = true
            message = null
            runCatching { action() }.onFailure { message = accountErrorMessage(it) }
            busy = false
        }
    }

    LaunchedEffect(auth, gateway) {
        if (auth != null && gateway != null) {
            busy = true
            runCatching {
                if (auth.isSignedIn()) {
                    gateway.bootstrap()
                    if (existingEnrollment != null) onAuthenticated() else step = OnboardingStep.Mode
                }
            }.onFailure { message = accountErrorMessage(it) }
            busy = false
        }
    }

    CloudSetupShell(
        title = when (step) {
            OnboardingStep.Auth -> "Your greenhouse account"
            OnboardingStep.Confirm -> "Verify your email"
            OnboardingStep.ResetPassword -> "Reset your password"
            OnboardingStep.Mode -> "Choose this phone's role"
            OnboardingStep.ServerPair -> "Pair your client phone"
            OnboardingStep.ClientPair -> "Connect to your greenhouse"
        },
        subtitle = when (step) {
            OnboardingStep.Auth -> "Sign in to monitor your greenhouse securely from this phone or another device."
            OnboardingStep.Confirm -> "Enter the verification code sent to your email."
            OnboardingStep.ResetPassword -> "Enter the code sent to your email and choose a new password."
            OnboardingStep.Mode -> "Use the older greenhouse phone as Server and your everyday phone as Client."
            OnboardingStep.ServerPair -> "On the client phone, sign in to this same account and scan this one-use code."
            OnboardingStep.ClientPair -> "Scan the QR code shown by the greenhouse Server phone."
        },
        message = message,
        busy = busy
    ) {
        when (step) {
            OnboardingStep.Auth -> {
                CloudField(email, { email = it }, "Email", KeyboardType.Email)
                CloudField(password, { password = it }, "Password", KeyboardType.Password, password = true)
                Text(
                    "For a new account: 12+ characters with uppercase, lowercase, a number, and a symbol.",
                    color = DashboardTokens.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Sign in", Modifier.fillMaxWidth(), busy) {
                        val manager = auth ?: return@PrimaryButton
                        val api = gateway ?: return@PrimaryButton
                        runAction {
                            val result = manager.signIn(email, password)
                            if (!result.isSignedIn) error("Additional account verification is required")
                            api.bootstrap()
                            if (existingEnrollment != null) onAuthenticated() else step = OnboardingStep.Mode
                        }
                    }
                    SecondaryButton("Create account", Modifier.fillMaxWidth(), busy) {
                        val manager = auth ?: return@SecondaryButton
                        newAccountPasswordIssue(password)?.let {
                            message = it
                            return@SecondaryButton
                        }
                        runAction {
                            val result = manager.signUp(email, password)
                            step = if (result.isSignUpComplete) OnboardingStep.Auth else OnboardingStep.Confirm
                            message = if (result.isSignUpComplete) "Account created. Sign in to continue." else null
                        }
                    }
                    SecondaryButton("Forgot password?", Modifier.fillMaxWidth(), busy) {
                        val manager = auth ?: return@SecondaryButton
                        if (email.isBlank()) {
                            message = "Enter your email, then choose Forgot password."
                            return@SecondaryButton
                        }
                        runAction {
                            manager.resetPassword(email)
                            password = ""
                            confirmationCode = ""
                            step = OnboardingStep.ResetPassword
                            message = "We sent a password reset code to your email."
                        }
                    }
                }
            }
            OnboardingStep.Confirm -> {
                Text(email, color = DashboardTokens.Muted, fontSize = 16.sp)
                CloudField(confirmationCode, { confirmationCode = it }, "Verification code", KeyboardType.Number)
                PrimaryButton("Verify email", Modifier.fillMaxWidth(), busy) {
                    val manager = auth ?: return@PrimaryButton
                    runAction {
                        manager.confirmSignUp(email, confirmationCode)
                        step = OnboardingStep.Auth
                        message = "Email verified. Sign in to continue."
                    }
                }
            }
            OnboardingStep.ResetPassword -> {
                Text(email, color = DashboardTokens.Muted, fontSize = 16.sp)
                CloudField(confirmationCode, { confirmationCode = it }, "Reset code", KeyboardType.Number)
                CloudField(password, { password = it }, "New password", KeyboardType.Password, password = true)
                Text(
                    "Use 12+ characters with uppercase, lowercase, a number, and a symbol.",
                    color = DashboardTokens.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Save new password", Modifier.fillMaxWidth(), busy) {
                        val manager = auth ?: return@PrimaryButton
                        newAccountPasswordIssue(password)?.let {
                            message = it
                            return@PrimaryButton
                        }
                        runAction {
                            manager.confirmResetPassword(email, password, confirmationCode)
                            confirmationCode = ""
                            step = OnboardingStep.Auth
                            message = "Password updated. Sign in with your new password."
                        }
                    }
                    SecondaryButton("Back to sign in", Modifier.fillMaxWidth(), busy) {
                        confirmationCode = ""
                        password = ""
                        message = null
                        step = OnboardingStep.Auth
                    }
                }
            }
            OnboardingStep.Mode -> {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoleButton("Greenhouse Server", "Uses this phone's sensors and front camera.", Modifier.fillMaxWidth(), busy) {
                        val api = gateway ?: return@RoleButton
                        runAction {
                            api.registerServerDevice(greenhouseId)
                            pendingServer = CloudEnrollment(deviceId, greenhouseId, CloudDeviceRole.GreenhouseServer)
                            challenge = api.createPairingChallenge(deviceId)
                            step = OnboardingStep.ServerPair
                        }
                    }
                    RoleButton("Viewer Client", "Views your greenhouse locally and remotely.", Modifier.fillMaxWidth(), busy) {
                        step = OnboardingStep.ClientPair
                    }
                }
            }
            OnboardingStep.ServerPair -> {
                val current = challenge
                if (current != null) {
                    val payload = pairingPayload(current.displayCode)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 520.dp
                        val details: @Composable ColumnScope.() -> Unit = {
                            Text("Expires in 5 minutes", color = DashboardTokens.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(current.displayCode, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            SecondaryButton("Refresh code", Modifier.fillMaxWidth(), busy) {
                                val api = gateway ?: return@SecondaryButton
                                runAction { challenge = api.createPairingChallenge(deviceId) }
                            }
                            PrimaryButton("Finish server setup", Modifier.fillMaxWidth(), busy) {
                                pendingServer?.let(onComplete)
                            }
                        }
                        if (compact) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Image(qrBitmap(payload).asImageBitmap(), "One-use greenhouse pairing QR code", Modifier.size(180.dp).clip(RoundedCornerShape(14.dp)))
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = details)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Image(qrBitmap(payload).asImageBitmap(), "One-use greenhouse pairing QR code", Modifier.size(190.dp).clip(RoundedCornerShape(14.dp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = details)
                            }
                        }
                    }
                }
            }
            OnboardingStep.ClientPair -> {
                CloudField(pairingCode, { pairingCode = it }, "Pairing code", KeyboardType.Ascii)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Scan QR code", Modifier.fillMaxWidth(), busy) {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the greenhouse Server code").setBeepEnabled(false).setOrientationLocked(false))
                    }
                    PrimaryButton("Pair client", Modifier.fillMaxWidth(), busy) {
                        val api = gateway ?: return@PrimaryButton
                        runAction {
                            val code = pairingCodeFromPayload(pairingCode) ?: error("Enter or scan a valid pairing code")
                            val registered = api.claimPairingChallenge(code, deviceId)
                            val pairedGreenhouse = registered.greenhouseId ?: error("Server registration is incomplete")
                            onComplete(CloudEnrollment(deviceId, pairedGreenhouse, CloudDeviceRole.ViewerClient))
                        }
                    }
                }
                SecondaryButton("Back", Modifier.fillMaxWidth(), busy) { step = OnboardingStep.Mode }
            }
        }
    }
}

@Composable
private fun CloudSetupShell(
    title: String,
    subtitle: String = "",
    message: String? = null,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DashboardTokens.BackgroundDeep)) {
        val portrait = maxHeight > maxWidth
        if (portrait) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingHeader(title, subtitle, Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 6.dp), 34)
                Spacer(Modifier.height(24.dp))
                OnboardingCard(Modifier.fillMaxWidth().widthIn(max = 560.dp), busy, message, content)
            }
        } else {
            val outer = if (maxWidth < 900.dp) 24.dp else 48.dp
            Row(Modifier.fillMaxSize().padding(outer), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                OnboardingHeader(title, subtitle, Modifier.weight(.42f).fillMaxHeight().padding(22.dp), 36)
                OnboardingCard(Modifier.weight(.58f).fillMaxHeight(), busy, message, content)
            }
        }
    }
}

@Composable
private fun OnboardingHeader(title: String, subtitle: String, modifier: Modifier, titleSize: Int) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("junecity.studio", color = DashboardTokens.Amber, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("Greenhouse Monitor", color = DashboardTokens.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color.White, fontSize = titleSize.sp, lineHeight = (titleSize + 4).sp, fontWeight = FontWeight.Black)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(subtitle, color = DashboardTokens.Muted, fontSize = 17.sp, lineHeight = 23.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Private • Encrypted • Easy pairing", color = DashboardTokens.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OnboardingCard(
    modifier: Modifier,
    busy: Boolean,
    message: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(DashboardTokens.Panel).border(1.dp, DashboardTokens.PanelEdge, RoundedCornerShape(28.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) CircularProgressIndicator(color = DashboardTokens.Amber, modifier = Modifier.size(34.dp))
        if (message != null) {
            Text(message, color = DashboardTokens.Danger, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        content()
    }
}

@Composable
private fun AppOrientation(monitoring: Boolean) {
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val orientation = if (monitoring) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    SideEffect {
        if (activity != null && activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun accountErrorMessage(error: Throwable): String {
    val raw = generateSequence(error) { it.cause }
        .take(6)
        .flatMap { cause ->
            sequenceOf(
                cause.message,
                cause::class.simpleName,
                (cause as? AuthException)?.recoverySuggestion
            ).filterNotNull()
        }
        .joinToString(" ")
    return when {
        raw.contains("already signed in", ignoreCase = true) ||
            raw.contains("signed in user", ignoreCase = true) ->
            "An older app session was cleared. Tap Sign in again."
        raw.contains("incorrect username or password", ignoreCase = true) ||
            raw.contains("user does not exist", ignoreCase = true) ||
            raw.contains("not authorized", ignoreCase = true) ||
            raw.contains("NotAuthorized", ignoreCase = true) ->
            "Email or password not recognized. Use Forgot password if this account already exists."
        raw.contains("password given is invalid", ignoreCase = true) ||
            raw.contains("password", ignoreCase = true) &&
            (raw.contains("policy", ignoreCase = true) || raw.contains("minimum", ignoreCase = true) || raw.contains("invalid", ignoreCase = true)) ->
            "Use at least 12 characters with uppercase, lowercase, a number, and a symbol."
        raw.contains("already exists", ignoreCase = true) || raw.contains("UsernameExists", ignoreCase = true) ->
            "An account already exists for this email. Sign in or use Forgot password."
        raw.contains("code", ignoreCase = true) &&
            (raw.contains("expired", ignoreCase = true) || raw.contains("mismatch", ignoreCase = true) || raw.contains("invalid", ignoreCase = true)) ->
            "That verification code is invalid or expired. Request a new code and try again."
        raw.contains("not confirmed", ignoreCase = true) ->
            "Verify this email with the confirmation code, then sign in."
        raw.contains("limit exceeded", ignoreCase = true) || raw.contains("too many", ignoreCase = true) ->
            "Too many attempts were made. Wait a few minutes, then try again."
        raw.contains("network", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ||
            raw.contains("unable to resolve host", ignoreCase = true) || raw.contains("connection", ignoreCase = true) ->
            "Unable to connect. Check your internet connection and try again."
        else -> "We couldn't complete that request. Please try again."
    }
}

internal fun newAccountPasswordIssue(password: String): String? =
    if (password.length < 12 || password.none(Char::isUpperCase) || password.none(Char::isLowerCase) ||
        password.none(Char::isDigit) || password.none { !it.isLetterOrDigit() }) {
        "Use at least 12 characters with uppercase, lowercase, a number, and a symbol."
    } else null

@Composable
private fun CloudField(value: String, onValue: (String) -> Unit, label: String, keyboard: KeyboardType, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = DashboardTokens.Amber, unfocusedBorderColor = DashboardTokens.PanelEdge,
            focusedLabelColor = DashboardTokens.Amber, unfocusedLabelColor = DashboardTokens.Muted,
            cursorColor = DashboardTokens.Amber
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    )
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier, disabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !disabled, modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = DashboardTokens.Background)
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier, disabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = !disabled, modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, DashboardTokens.PanelEdge)
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun RoleButton(title: String, body: String, modifier: Modifier, disabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !disabled, modifier = modifier.height(150.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.PanelRaised, contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, DashboardTokens.PanelEdge)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text(body, color = DashboardTokens.Muted, fontSize = 14.sp, textAlign = TextAlign.Start)
        }
    }
}

private fun qrBitmap(value: String, size: Int = 640): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

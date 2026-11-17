package studio.junecity.greenhouse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*
import org.json.JSONObject
import studio.junecity.greenhouse.camera.*
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient

@Composable
internal fun CameraContent(
    state: GreenhouseUiState,
    d: (Float) -> Dp,
    t: (Float) -> TextUnit,
    onQuality: (CameraStreamQuality) -> Unit,
) {
    val context = LocalContext.current
    val secure = remember(context) { SecureSettingsStore(context) }
    var enrollment by remember(secure) { mutableStateOf(secure.cloudEnrollment()) }
    DisposableEffect(secure) {
        val unsubscribe = secure.watchEnrollment { enrollment = secure.cloudEnrollment() }
        enrollment = secure.cloudEnrollment()
        onDispose { unsubscribe() }
    }
    if (state.standalone || enrollment.isStandaloneClient()) {
        CameraMessage(
            "Add a greenhouse camera",
            "Pair a second phone in Settings → Connectivity for live video, clips and timelapses.",
            d,
            t,
        )
        return
    }
    val house = enrollment?.greenhouseId.orEmpty()
    if (enrollment == null && CloudRuntime.configurationOrNull() != null) {
        CameraMessage(
            "Pair a greenhouse phone",
            "Open Settings → Connectivity to pair this phone with your greenhouse.",
            d,
            t,
        )
        return
    }
    val accountScope = enrollment?.let { "${it.tenantId}/${it.deviceId}" } ?: "local-install"
    key(accountScope, house, state.preferences.activeGreenhouseId, state.preferences.cameraMode) {
        val movies = remember {
            CameraMovies(
                File(context.noBackupFilesDir, "camera-library"),
                accountScope,
                "$house/${state.preferences.activeGreenhouseId}",
            )
        }
        val api =
            remember(enrollment) {
                runCatching {
                        CloudRuntime.configurationOrNull()?.let { config ->
                            enrollment?.let { gatewayForEnrollment(context, config, it) }
                        }
                    }
                    .getOrNull()
            }
        val server =
            enrollment?.let { it.role == CloudDeviceRole.GreenhouseServer }
                ?: (state.preferences.cameraMode == CameraMode.CameraServer)
        CameraWorkspace(state, movies, house, api, d, t, onQuality, server)
    }
}

@Composable
internal fun CameraWorkspace(
    state: GreenhouseUiState,
    movies: CameraMovies,
    house: String,
    api: AuthenticatedCloudGateway?,
    d: (Float) -> Dp,
    t: (Float) -> TextUnit,
    onQuality: (CameraStreamQuality) -> Unit,
    server: Boolean = state.preferences.cameraMode == CameraMode.CameraServer,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val frames = remember { CameraSamples() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }
    LaunchedEffect(server) { if (server && !permission) ask.launch(Manifest.permission.CAMERA) }
    var live by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf<MovieKind?>(null) }
    var remaining by remember { mutableIntStateOf(0) }
    var stop by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var playback by remember { mutableStateOf<Uri?>(null) }
    var remove by remember { mutableStateOf<File?>(null) }
    var cloud by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(false) }
    var checkout by remember { mutableStateOf(false) }
    var accessRevision by remember { mutableIntStateOf(0) }
    val files by
        produceState(emptyList<File>(), movies, revision) {
            value = withContext(Dispatchers.IO) { movies.library() }
        }
    if (checkout && api != null)
        VideoSubscriptionDialog(api, house) {
            checkout = false
            accessRevision++
        }
    var catalog by remember { mutableStateOf(false) }
    var accessStatus by remember { mutableStateOf("") }
    LaunchedEffect(tab, cloud, accessRevision) {
        if ((tab != 3 && !(tab == 0 && server) && !(tab == 1 && cloud)) || api == null || house.isBlank())
            return@LaunchedEffect
        paid = false
        catalog = false
        accessStatus = "Checking account…"
        try {
            val access = api.archiveRequest(house, "access")
            paid = access.optBoolean("active")
            catalog = access.optJSONObject("checkout") != null
            accessStatus =
                when {
                    paid -> "Cloud recording is active."
                    catalog -> "Available as a separate subscription."
                    else -> "Cloud recording is unavailable for this account."
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            accessStatus = "Cloud recording is unavailable. Local recordings are ready to use."
        }
    }
    suspend fun upload(file: File) {
        checkNotNull(api).uploadMovie(house, file)
        check(withContext(Dispatchers.IO) { movies.delete(file) }) { "Could not clear the saved upload" }
        revision++
    }
    fun capture(kind: MovieKind) {
        if (job?.isCompleted == false) return
        stop = false
        recording = kind
        remaining = (kind.durationMs / 1000).toInt()
        message = ""
        job =
            scope.launch {
                try {
                    if (kind == MovieKind.Cloud)
                        check(
                            checkNotNull(api).archiveRequest(house, "access").optBoolean("active")
                        )
                    val file = movies.record(kind, frames::next, { remaining = it }, { stop })
                    revision++
                    if (kind == MovieKind.Cloud) {
                        message = "Uploading clip…"
                        upload(file)
                        message = "Saved to cloud playback."
                    } else message = "Saved to Playback on this phone."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CloudApiException) {
                    message = if (e.statusCode == 429) "Daily cloud upload limit reached. Retry the saved clip tomorrow in Playback."
                    else "Cloud recording is unavailable. Check your subscription and pending clips in Playback."
                } catch (_: Exception) {
                    message =
                        if (kind == MovieKind.Cloud)
                            "Cloud recording failed. Check Playback for a pending upload."
                        else "Could not save the clip. Check the camera feed and free space."
                } finally {
                    recording = null
                }
            }
    }
    DisposableEffect(lifecycle) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && paused) accessRevision++
            if (event == Lifecycle.Event.ON_PAUSE) {
                paused = true
                job?.cancel()
                playback = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
    val report: (Boolean, String?) -> Unit = { active, detail ->
        live = active
        status = detail
    }
    Column(Modifier.fillMaxSize()) {
        CameraControls(tab, d, t) { next ->
            if (next != tab) {
                job?.cancel()
                if (!server || tab !in setOf(0, 2) || next !in setOf(0, 2)) {
                    live = false
                    status = null
                }
                tab = next
                message = ""
            }
        }
        Spacer(Modifier.height(d(14f)))
        if (tab == 1) {
            NeonPanel(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                ) {
                    Row {
                        CameraAction("On this phone", !cloud) { cloud = false }
                        CameraAction("Cloud library", cloud) { cloud = true }
                    }
                    if (cloud) {
                        if (paid && !server && api != null) {
                            AccountCameraLibrary(api, house, state.preferences.activeGreenhouse.name) { selected, changes ->
                                CloudMovies(api, selected, revision + changes, { playback = it }, { tab = 3 })
                            }
                        } else if (paid) CloudMovies(api, house, revision, { playback = it }, { tab = 3 })
                        else {
                            CameraNote(accessStatus.ifBlank {
                                if (api == null || house.isBlank()) "Cloud recording requires a paired account."
                                else "Checking cloud storage…"
                            })
                            if (catalog && !server) {
                                CameraNote("Store one-minute greenhouse clips for seven days and play them on either phone.")
                                CameraAction("View subscription", true) { checkout = true }
                            }
                            if (api != null) CameraAction("Check access") { accessRevision++ }
                        }
                    } else {
                        if (files.isEmpty())
                            CameraNote("Your saved clips and timelapses will appear here.")
                        files.forEach { file ->
                            val date =
                                file.name.substringBefore('-').toLongOrNull() ?: return@forEach
                            val kind =
                                MovieKind.entries.firstOrNull { file.name.contains("-${it.name}-") }
                                    ?: return@forEach
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (kind == MovieKind.Cloud) "Pending cloud clip"
                                        else kind.label,
                                        color = Color.White,
                                    )
                                    CameraNote(movieDate(date))
                                }
                                CameraAction("Play") { playback = Uri.fromFile(file) }
                                if (kind == MovieKind.Cloud && server && api != null)
                                    CameraAction("Retry upload") {
                                        if (job?.isCompleted != false)
                                            job =
                                                scope.launch {
                                                    try {
                                                        upload(file)
                                                        message = "Saved to cloud playback."
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        message = if (e is CloudApiException && e.statusCode == 429)
                                                            "Daily cloud upload limit reached. Retry tomorrow."
                                                        else "Upload unavailable. The clip is still on this phone."
                                                    }
                                                }
                                    }
                                CameraAction("Delete") { remove = file }
                            }
                        }
                    }
                    if (message.isNotEmpty()) CameraNote(message)
                }
            }
        } else if (tab == 3) {
            NeonPanel(Modifier.fillMaxSize()) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (server) {
                        CameraHeading("Local camera quality")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CameraStreamQuality.entries.forEach { quality ->
                                CameraAction(
                                    if (quality == CameraStreamQuality.DataSaver) "Data saver"
                                    else quality.name,
                                    state.preferences.cameraStreamQuality == quality,
                                ) {
                                    onQuality(quality)
                                }
                            }
                        }
                    }
                    CameraHeading("Recording storage")
                    CameraNote(
                        "Short clips and timelapses stay on the phone that records them. Delete them in Playback."
                    )
                    CameraHeading("Cloud recording")
                    CameraNote(
                        "One subscription covers your account’s camera phones. Save up to 24 one-minute clips per day across all greenhouses and replay them for seven days."
                    )
                    CameraNote(
                        accessStatus.ifBlank { "Cloud recording requires a paired account." }
                    )
                    if (!paid && catalog && !server)
                        CameraAction("View subscription", true) { checkout = true }
                    if (paid && !server) {
                        CameraAction("Manage subscription") {
                            val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
                                .appendQueryParameter("sku", ArchivePurchaseWorker.PRODUCT)
                                .appendQueryParameter("package", context.packageName).build()
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                message = "Open Google Play → Payments & subscriptions → Subscriptions."
                            }
                        }
                    }
                    if (message.isNotBlank()) CameraNote(message)
                    if (api != null) CameraAction("Check access") { accessRevision++ }
                    if (paid && server) CameraAction("Open camera", true) { tab = 0 }
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d(14f))) {
                NeonPanel(Modifier.weight(.67f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalCameraSamples provides frames) {
                        when {
                            tab == 2 && !server ->
                                CameraMessage(
                                    "Greenhouse timelapse",
                                    "Start a timelapse on the greenhouse phone and find it in Playback there.",
                                    d,
                                    t,
                                )
                            !server -> RemoteCameraFeed(d, t, Modifier.fillMaxSize(), report)
                            !permission ->
                                CameraMessage(
                                    "Camera access required",
                                    "Allow the greenhouse phone to use its camera.",
                                    d,
                                    t,
                                ) {
                                    ask.launch(Manifest.permission.CAMERA)
                                }
                            else ->
                                FrontCameraFeed(
                                    d,
                                    t,
                                    Modifier.fillMaxSize(),
                                    state.preferences.cameraStreamQuality,
                                    report,
                                )
                        }
                    }
                }
                NeonPanel(Modifier.weight(.33f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        CameraHeading(
                            if (tab == 2) "Timelapse"
                            else if (server) "Greenhouse camera" else "Live View"
                        )
                        if (tab == 0)
                            CameraNote(
                                status ?: if (live) "Camera ready" else "Ready for Live View"
                            )
                        if (tab == 2 && server)
                            CameraNote(
                                "One frame every 5 seconds for 5 minutes. Keep this tab open."
                            )
                        if (recording != null) {
                            CameraNote("${recording!!.label} · ${remaining}s")
                            CameraAction("Finish", true) { stop = true }
                        } else if (tab == 0 && live) {
                            CameraAction("Save short clip", true) { capture(MovieKind.Clip) }
                            CameraNote(
                                if (server) "Up to 15 seconds on this phone."
                                else "Saves the remainder of this Live View on this phone."
                            )
                            if (server && paid)
                                CameraAction("Record 1 min to cloud") { capture(MovieKind.Cloud) }
                        } else if (tab == 2 && server && live)
                            CameraAction("Start timelapse", true) { capture(MovieKind.Timelapse) }
                        if (message.isNotEmpty()) CameraNote(message)
                    }
                }
            }
        }
    }
    playback?.let { MoviePlayer(it) { playback = null } }
    remove?.let { file ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Delete this recording?") },
            text = { Text("This removes the saved video from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!movies.delete(file)) message = "Could not delete recording."
                        revision++
                        remove = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun CloudMovies(
    api: AuthenticatedCloudGateway?,
    house: String,
    revision: Int,
    play: (Uri) -> Unit,
    settings: () -> Unit,
) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, Long>>()) }
    var message by remember { mutableStateOf("Loading cloud recordings…") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api, house, revision, refresh) {
        try {
            val list = checkNotNull(api).archiveRequest(house, "list").getJSONArray("clips")
            rows =
                List(list.length()) {
                    list.getJSONObject(it).let { clip ->
                        clip.getString("id") to clip.getLong("time")
                    }
                }
            message = if (rows.isEmpty()) "No cloud recordings in the last seven days." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            rows = emptyList()
            message = "Cloud library unavailable. Check your storage access in Camera settings."
        }
    }
    if (message.isNotBlank()) CameraNote(message)
    Row {
        CameraAction("Refresh") { refresh++ }
        CameraAction("Camera settings", onClick = settings)
    }
    var deleting by remember { mutableStateOf<String?>(null) }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete cloud recording?") },
            text = {
                Text("This removes the recording for all phones on this greenhouse account.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            try {
                                checkNotNull(api)
                                    .archiveRequest(house, "delete", JSONObject().put("id", id))
                                refresh++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                message = "Could not delete this recording."
                            }
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
    rows.forEach { (id, time) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraAction("Play · ${movieDate(time)}") {
                scope.launch {
                    try {
                        val url =
                            checkNotNull(api)
                                .archiveRequest(house, "play", JSONObject().put("id", id))
                                .getString("url")
                        val uri = Uri.parse(url)
                        check(uri.scheme == "https")
                        play(uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        message = "Could not open this cloud recording."
                    }
                }
            }
            CameraAction("Delete") { deleting = id }
        }
    }
}

private fun movieDate(time: Long) =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

@Composable
private fun CameraHeading(s: String) =
    Text(s, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)

@Composable
private fun CameraNote(s: String) = Text(s, color = DashboardTokens.Muted, fontSize = 14.sp)

@Composable
private fun CameraAction(s: String, primary: Boolean = false, onClick: () -> Unit) {
    if (primary)
        Button(
            onClick = onClick,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = DashboardTokens.Amber,
                    contentColor = Color.Black,
                ),
        ) {
            Text(s)
        }
    else TextButton(onClick = onClick) { Text(s, color = DashboardTokens.Amber) }
}

@Composable
private fun MoviePlayer(uri: Uri, close: () -> Unit) {
    var error by remember(uri) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = DashboardTokens.Background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback", Modifier.weight(1f), color = Color.White, fontSize = 20.sp)
                    CameraAction("Close", onClick = close)
                }
                if (error) CameraNote("This recording could not be played. Close and try again.")
                else
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controls = MediaController(ctx)
                                setMediaController(controls)
                                controls.setAnchorView(this)
                                setOnErrorListener { _, _, _ ->
                                    error = true
                                    true
                                }
                                setVideoURI(uri)
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.stopPlayback() },
                    )
            }
        }
    }
}

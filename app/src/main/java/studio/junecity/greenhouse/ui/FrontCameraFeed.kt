package studio.junecity.greenhouse.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import studio.junecity.greenhouse.camera.CameraFrameHub
import studio.junecity.greenhouse.camera.CameraStreamProfile
import studio.junecity.greenhouse.model.CameraStreamQuality
import studio.junecity.greenhouse.streaming.CloudCameraLease
import java.util.concurrent.Executors
import android.os.Build
import android.util.Size

@Composable fun FrontCameraFeed(d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier=Modifier,streamQuality:CameraStreamQuality=CameraStreamQuality.Balanced,onStatus:(Boolean,String?)->Unit={_,_->}){
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // On the Android 10 Moto G7, detaching a TextureView can block HWUI in the Adreno driver.
            implementationMode = if (Build.VERSION.SDK_INT == 29 && Build.DEVICE == "river") {
                PreviewView.ImplementationMode.PERFORMANCE
            } else {
                PreviewView.ImplementationMode.COMPATIBLE
            }
            clipToOutline = true
        }
    }
    val profile=remember(streamQuality){CameraStreamProfile.from(streamQuality)}
    val remoteActive by CloudCameraLease.remoteActive.collectAsState()
    val analysisExecutor=remember(streamQuality,remoteActive){Executors.newSingleThreadExecutor()}
    var active by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("Starting front camera…")}
    val previewAlpha by animateFloatAsState(if(active)1f else .25f,tween(accessibleMotionDuration(AppMotion.CameraConnectionFadeMs)),label="camera connection")
    DisposableEffect(lifecycleOwner,streamQuality,remoteActive){
        if(remoteActive){active=true;message="Live front camera";onStatus(true,null);onDispose{analysisExecutor.shutdown()}}
        else{
        val future=ProcessCameraProvider.getInstance(context)
        var provider:ProcessCameraProvider?=null
        var boundCases = emptyArray<androidx.camera.core.UseCase>()
        var disposed=false
        val listener=Runnable{
            if(disposed || CloudCameraLease.remoteActive.value) return@Runnable
            runCatching{
                val cameraProvider=future.get();provider=cameraProvider
                val selector=when{cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)->CameraSelector.DEFAULT_FRONT_CAMERA;cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)->CameraSelector.DEFAULT_BACK_CAMERA;else->error("No camera available")}
                val preview=Preview.Builder().build().also{it.surfaceProvider=previewView.surfaceProvider}
                val analysis=ImageAnalysis.Builder().setTargetResolution(Size(profile.width,profile.height)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also{it.setAnalyzer(analysisExecutor,CameraFrameHub.analyzer(profile))}
                boundCases = arrayOf(preview, analysis)
                cameraProvider.unbindAll();cameraProvider.bindToLifecycle(lifecycleOwner,selector,preview,analysis)
                active=true;message="Live front camera";onStatus(true,null)
            }.onFailure{active=false;message=it.message?:"Camera unavailable";onStatus(false,message)}
        }
        future.addListener(listener,ContextCompat.getMainExecutor(context))
        onDispose{disposed=true;provider?.unbind(*boundCases);analysisExecutor.shutdown();onStatus(false,null)}
        }
    }
    Box(modifier.clip(RoundedCornerShape(22.dp))){
        if (remoteActive) PeerVideoSurface(CloudCameraLease.preview, Modifier.fillMaxSize(), mirror = true)
        else AndroidView(factory={previewView},modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).alpha(previewAlpha))
        Row(Modifier.align(Alignment.TopEnd).padding(d(14f)).background(Color(0xD9101113),RoundedCornerShape(d(20f))).padding(horizontal=d(14f),vertical=d(7f)),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(d(9f)).background(if(active)DashboardTokens.Green else DashboardTokens.Amber,CircleShape));Spacer(Modifier.width(d(8f)));Text(message,color=Color.White,fontSize=t(11f),fontWeight=FontWeight.Bold)
        }
    }
}

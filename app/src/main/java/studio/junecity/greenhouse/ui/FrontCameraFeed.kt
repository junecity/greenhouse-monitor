package studio.junecity.greenhouse.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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

@Composable fun FrontCameraFeed(d:(Float)->androidx.compose.ui.unit.Dp,t:(Float)->androidx.compose.ui.unit.TextUnit,modifier:Modifier=Modifier){
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val previewView=remember{PreviewView(context).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE;clipToOutline=true}}
    var active by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("Starting front camera…")}
    DisposableEffect(lifecycleOwner){
        val future=ProcessCameraProvider.getInstance(context)
        var provider:ProcessCameraProvider?=null
        val listener=Runnable{
            runCatching{
                val cameraProvider=future.get();provider=cameraProvider
                val selector=when{cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)->CameraSelector.DEFAULT_FRONT_CAMERA;cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)->CameraSelector.DEFAULT_BACK_CAMERA;else->error("No camera available")}
                val preview=Preview.Builder().build().also{it.surfaceProvider=previewView.surfaceProvider}
                cameraProvider.unbindAll();cameraProvider.bindToLifecycle(lifecycleOwner,selector,preview)
                active=true;message="Live front camera"
            }.onFailure{active=false;message=it.message?:"Camera unavailable"}
        }
        future.addListener(listener,ContextCompat.getMainExecutor(context))
        onDispose{provider?.unbindAll()}
    }
    Box(modifier.clip(RoundedCornerShape(22.dp))){
        AndroidView(factory={previewView},modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)))
        Row(Modifier.align(Alignment.TopEnd).padding(d(14f)).background(Color(0xD9101113),RoundedCornerShape(d(20f))).padding(horizontal=d(14f),vertical=d(7f)),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(d(9f)).background(if(active)Color(0xFF61E85A) else Color(0xFFFFB545),CircleShape));Spacer(Modifier.width(d(8f)));Text(message,color=Color.White,fontSize=t(11f),fontWeight=FontWeight.Bold)
        }
    }
}

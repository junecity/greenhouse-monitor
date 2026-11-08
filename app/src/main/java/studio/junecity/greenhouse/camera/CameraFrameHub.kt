package studio.junecity.greenhouse.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import studio.junecity.greenhouse.model.CameraStreamQuality

data class CameraStreamProfile(val width:Int,val height:Int,val framesPerSecond:Int,val jpegQuality:Int){
    val minimumFrameIntervalNs:Long get()=1_000_000_000L/framesPerSecond
    companion object{fun from(quality:CameraStreamQuality)=when(quality){
        CameraStreamQuality.DataSaver->CameraStreamProfile(480,270,4,58)
        CameraStreamQuality.Balanced->CameraStreamProfile(640,360,7,68)
        CameraStreamQuality.High->CameraStreamProfile(1280,720,10,76)
    }}
}

object CameraFrameHub {
    private val latest = AtomicReference<ByteArray?>(null)
    fun current(): ByteArray? = latest.get()
    fun analyzer(profile:CameraStreamProfile): ImageAnalysis.Analyzer {
        val lastFrame=AtomicLong(0)
        return ImageAnalysis.Analyzer { image ->
            try {
                val now=System.nanoTime()
                val previous=lastFrame.get()
                if(now-previous>=profile.minimumFrameIntervalNs&&lastFrame.compareAndSet(previous,now))latest.set(image.toJpeg(profile))
            } finally { image.close() }
        }
    }
}

private fun ImageProxy.toJpeg(profile:CameraStreamProfile): ByteArray {
    val outputWidth=profile.width.coerceAtMost(width).and(-2).coerceAtLeast(2)
    val outputHeight=profile.height.coerceAtMost(height).and(-2).coerceAtLeast(2)
    val targetAspect=outputWidth.toDouble()/outputHeight
    val sourceAspect=width.toDouble()/height
    val cropWidth:Int
    val cropHeight:Int
    val cropLeft:Int
    val cropTop:Int
    if(sourceAspect>targetAspect){cropHeight=height;cropWidth=(height*targetAspect).toInt().and(-2);cropLeft=((width-cropWidth)/2).and(-2);cropTop=0}
    else{cropWidth=width;cropHeight=(width/targetAspect).toInt().and(-2);cropLeft=0;cropTop=((height-cropHeight)/2).and(-2)}
    val nv21 = ByteArray(outputWidth * outputHeight * 3 / 2)
    val y = planes[0]
    val u = planes[1]
    val v = planes[2]
    val yBuffer=y.buffer.duplicate();val uBuffer=u.buffer.duplicate();val vBuffer=v.buffer.duplicate()
    for(outputY in 0 until outputHeight){
        val sourceY=cropTop+outputY*cropHeight/outputHeight
        for(outputX in 0 until outputWidth){
            val sourceX=cropLeft+outputX*cropWidth/outputWidth
            nv21[outputY*outputWidth+outputX]=yBuffer.get(sourceY*y.rowStride+sourceX*y.pixelStride)
        }
    }
    val uvOffset=outputWidth*outputHeight
    for(outputY in 0 until outputHeight/2){
        val sourceY=(cropTop+outputY*2*cropHeight/outputHeight)/2
        for(outputX in 0 until outputWidth/2){
            val sourceX=(cropLeft+outputX*2*cropWidth/outputWidth)/2
            val destination=uvOffset+outputY*outputWidth+outputX*2
            nv21[destination]=vBuffer.get(sourceY*v.rowStride+sourceX*v.pixelStride)
            nv21[destination+1]=uBuffer.get(sourceY*u.rowStride+sourceX*u.pixelStride)
        }
    }
    return ByteArrayOutputStream().use { out ->
        YuvImage(nv21, ImageFormat.NV21, outputWidth, outputHeight, null)
            .compressToJpeg(Rect(0, 0, outputWidth, outputHeight), profile.jpegQuality, out)
        out.toByteArray()
    }
}

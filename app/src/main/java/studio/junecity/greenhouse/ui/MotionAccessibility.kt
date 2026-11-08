package studio.junecity.greenhouse.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Applies Android's animator-duration accessibility preference to app motion. */
@Composable
fun accessibleMotionDuration(baseMillis:Int):Int{
    val context=LocalContext.current
    var scale by remember{mutableFloatStateOf(readAnimatorScale(context))}
    DisposableEffect(context){
        val uri=Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){scale=readAnimatorScale(context)}}
        context.contentResolver.registerContentObserver(uri,false,observer)
        onDispose{context.contentResolver.unregisterContentObserver(observer)}
    }
    return if(scale<=0f)0 else (baseMillis*scale.coerceIn(.25f,2f)).roundToInt()
}

private fun readAnimatorScale(context:android.content.Context)=runCatching{Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)}.getOrDefault(1f)

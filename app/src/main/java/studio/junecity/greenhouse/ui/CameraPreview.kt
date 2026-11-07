package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.R

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, DashboardTokens.PanelEdge, RoundedCornerShape(18.dp))) {
        Image(painterResource(R.drawable.camera_demo), "Demonstration greenhouse camera preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

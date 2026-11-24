package dev.lolokeksu.scrimroom.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DocumentPreview(uri:Uri){
    val resolver=LocalContext.current.contentResolver
    var bitmap by remember(uri){mutableStateOf<Bitmap?>(null)}
    var failed by remember(uri){mutableStateOf(false)}
    LaunchedEffect(uri){
        val decoded=withContext(Dispatchers.IO){
            runCatching {
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                require(bounds.outWidth>0 && bounds.outHeight>0)
                var sample=1
                while(bounds.outWidth.toLong()/sample>1024L || bounds.outHeight.toLong()/sample>1024L) sample*=2
                val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
                resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)}
            }.getOrNull()
        }
        bitmap=decoded;failed=decoded==null
    }
    val image=bitmap
    when{
        image!=null->Image(image.asImageBitmap(),contentDescription="Выбранный скриншот",modifier=Modifier.fillMaxWidth().heightIn(max=300.dp),contentScale=ContentScale.Fit)
        failed->Text("Не удалось открыть изображение. Выбери файл повторно.",color=Red,fontSize=13.sp)
        else->CircularProgressIndicator(modifier=Modifier.size(28.dp))
    }
}

package me.rerere.rikkahub.ui.components.message

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Share01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AdaptiveMediaCard(
    imageUrl: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPreview by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val placeholder = if (LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val coilModel = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .placeholder(placeholder)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 340.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        AsyncImage(
            model = coilModel,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 340.dp)
                .shimmer(isLoading = isLoading)
                .clickable { showPreview = true },
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            onLoading = { isLoading = true },
            onSuccess = { isLoading = false },
            onError = { isLoading = false }
        )

        // Floating Quick Actions Bar (Top-Right)
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lightbox
                IconButton(
                    onClick = { showPreview = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = HugeIcons.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Download / Save
                IconButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            saveImageToGallery(context, imageUrl)
                            isSaving = false
                        }
                    },
                    modifier = Modifier.size(32.dp),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Download01,
                            contentDescription = "Save",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Share
                IconButton(
                    onClick = {
                        scope.launch {
                            shareImage(context, imageUrl)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = HugeIcons.Share01,
                        contentDescription = "Share",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (showPreview) {
        ImagePreviewDialog(
            model = imageUrl,
            onDismissRequest = { showPreview = false }
        )
    }
}

private suspend fun saveImageToGallery(context: Context, imageUrl: String) = withContext(Dispatchers.IO) {
    try {
        val bitmap = loadBitmap(context, imageUrl) ?: error("Failed to decode image")
        val filename = "RikkaHub_${System.currentTimeMillis()}.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RikkaHub")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RikkaHub")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Saved to Pictures/RikkaHub", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun shareImage(context: Context, imageUrl: String) = withContext(Dispatchers.IO) {
    try {
        val bitmap = loadBitmap(context, imageUrl) ?: error("Failed to load image for sharing")
        val cacheFile = File(context.cacheDir, "shared_image_${System.currentTimeMillis()}.png")
        FileOutputStream(cacheFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun loadBitmap(context: Context, urlOrData: String): Bitmap? {
    return if (urlOrData.startsWith("data:image")) {
        val b64 = urlOrData.substringAfter("base64,")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } else if (urlOrData.startsWith("file://") || urlOrData.startsWith("/")) {
        val path = urlOrData.removePrefix("file://")
        BitmapFactory.decodeFile(path)
    } else {
        val conn = URL(urlOrData).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.inputStream.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }
}

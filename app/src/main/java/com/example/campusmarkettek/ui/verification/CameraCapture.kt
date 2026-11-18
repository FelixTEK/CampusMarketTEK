package com.example.campusmarkettek.ui.verification

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.campusmarkettek.BuildConfig
import java.io.File

/**
 * กล้องถ่ายรูปบัตร/เซลฟี (CameraX) — ถ่ายจากกล้องเท่านั้น ไม่ให้เลือกจากคลังภาพ (ลดโอกาสใช้รูปที่แก้ไขมา)
 * ผลลัพธ์คือ JPEG ที่ย่อแล้ว (ไฟล์ชั่วคราวถูกลบทันทีหลังอ่าน ไม่ทิ้งรูปบัตรไว้ในเครื่อง)
 * ข้อยกเว้น: บิลด์ debug มีปุ่มเลือกจากคลังภาพ เพราะอีมูเลเตอร์บางตัวไม่มีกล้องจริง
 */
@Composable
fun CameraCapture(
    useFrontCamera: Boolean,
    onCaptured: (ByteArray) -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                onCaptured(ImageCompressor.compress(bytes))
            } catch (e: Exception) {
                onFailure("อ่านรูปไม่ได้ กรุณาลองใหม่")
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!granted) {
            Text("ต้องอนุญาตให้ใช้กล้องเพื่อถ่ายรูปยืนยันตัวตน", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text("อนุญาตใช้กล้อง")
            }
        } else {
            val imageCapture = remember { ImageCapture.Builder().build() }
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

            // ผูกกล้องกับ lifecycle: หน้าจอหยุด กล้องปิดเอง
            DisposableEffect(useFrontCamera, lifecycleOwner) {
                val future = ProcessCameraProvider.getInstance(context)
                val listener = Runnable {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                    } catch (e: Exception) {
                        onFailure("เปิดกล้องไม่ได้ในเครื่องนี้")
                    }
                }
                future.addListener(listener, ContextCompat.getMainExecutor(context))
                onDispose { runCatching { future.get().unbindAll() } }
            }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp)),
            )
            Button(
                onClick = {
                    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                try {
                                    onCaptured(ImageCompressor.compress(file.readBytes()))
                                } catch (e: Exception) {
                                    onFailure("ถ่ายรูปไม่สำเร็จ กรุณาลองใหม่")
                                } finally {
                                    file.delete() // ไม่ทิ้งรูปบัตรไว้ในแคชของเครื่อง
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                onFailure("ถ่ายรูปไม่สำเร็จ กรุณาลองใหม่")
                            }
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("ถ่ายรูป") }
        }

        if (BuildConfig.DEBUG) {
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            ) { Text("[debug] เลือกจากคลังภาพ") }
        }
    }
}

package com.example.campusmarkettek.ui.listing

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.campusmarkettek.domain.Category
import com.example.campusmarkettek.ui.components.AppTextField
import com.example.campusmarkettek.ui.components.ErrorView
import com.example.campusmarkettek.ui.components.LoadingView
import com.example.campusmarkettek.ui.verification.CameraCapture
import com.example.campusmarkettek.ui.verification.ImageCompressor

/**
 * หน้า 05 ลงขาย (ลงใหม่ / แก้ไขประกาศ / แก้แบบร่าง)
 * ทุกช่องตรงกับคอลัมน์ในตาราง listings และกติกา BR04
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListingFormScreen(
    onResult: (FormResult) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ListingFormViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    // ลงเสร็จแล้วล้างฟอร์มก่อนไปหน้าถัดไป
    val finish: (FormResult) -> Unit = { result ->
        if (result is FormResult.Queued) viewModel.reset()
        onResult(result)
    }
    val context = LocalContext.current
    var pickingCategory by rememberSaveable { mutableStateOf(false) }
    var cameraOpen by rememberSaveable { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        val room = MAX_PHOTOS - s.photos.size
        uris.take(room).forEach { uri ->
            try {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                viewModel.addPhoto(ImageCompressor.compress(bytes))
            } catch (e: Exception) {
                viewModel.onError("อ่านรูปไม่ได้ กรุณาลองใหม่")
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "ย้อนกลับ") }
            }
            Text(
                if (s.isEdit) "แก้ไขประกาศ" else "ลงขาย",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = if (onBack == null) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp),
            )
        }

        if (s.loading) {
            LoadingView()
            return@Column
        }
        if (s.error != null && s.categories.isEmpty() && s.isEdit) {
            ErrorView(s.error!!, onRetry = viewModel::load)
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── รูปสินค้า (ลงใหม่เท่านั้น) ──
            if (!s.isEdit) {
                Text("รูปสินค้า · ${s.photos.size} / $MAX_PHOTOS (รูปแรกเป็นรูปปก)", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    itemsIndexed(s.photos) { index, bytes -> PhotoTile(bytes, isCover = index == 0, onRemove = { viewModel.removePhoto(index) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { cameraOpen = true },
                        enabled = s.photos.size < MAX_PHOTOS,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp)); Text("  กล้อง") }
                    OutlinedButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = s.photos.size < MAX_PHOTOS,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Text("  คลังภาพ") }
                }
                if (s.showErrors && s.photosError != null) FieldError(s.photosError!!)
            }

            // ── หมวดหมู่ ──
            Text("หมวดหมู่", style = MaterialTheme.typography.titleSmall)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium).clickable { pickingCategory = true },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.categoryPath.isEmpty()) "เลือกหมวดหมู่" else s.categoryPath.joinToString(" › ") { it.nameTh },
                        modifier = Modifier.weight(1f),
                        color = if (s.categoryPath.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
            }
            if (s.showErrors && s.categoryError != null) FieldError(s.categoryError!!)

            // ── ชื่อ ราคา รายละเอียด ──
            AppTextField(
                s.title, viewModel::onTitle, "ชื่อประกาศ",
                supportingText = if (s.showErrors) s.titleError else "3–100 ตัวอักษร เช่น ชื่อหนังสือ ฉบับที่ หรือยี่ห้อรุ่น",
            )
            AppTextField(
                s.price, viewModel::onPrice, "ราคา (บาท)", keyboardType = KeyboardType.Number,
                supportingText = if (s.showErrors) s.priceError else "จำนวนเต็ม มากกว่า 0",
            )
            AppTextField(
                s.description, viewModel::onDescription, "รายละเอียด", singleLine = false,
                supportingText = "เขียนสภาพ ตำหนิ สเปก จำนวนในชุด และรหัสวิชา ผู้ซื้อค้นจากข้อความนี้ · ${s.description.length} / 2,000",
                modifier = Modifier.height(150.dp),
            )

            // ── คณะ (เฉพาะหนังสือ) ──
            if (s.isBook) {
                Text("คณะที่ใช้เล่มนี้ (เลือกได้หลายคณะ)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val all = s.faculties.map { it.id }.toSet()
                    FilterChip(
                        selected = all.isNotEmpty() && s.facultyIds == all,
                        onClick = { viewModel.setAllFaculties(!(all.isNotEmpty() && s.facultyIds == all)) },
                        label = { Text("ทุกคณะ") },
                    )
                    s.faculties.forEach { f ->
                        FilterChip(
                            selected = f.id in s.facultyIds,
                            onClick = { viewModel.toggleFaculty(f.id) },
                            label = { Text(f.nameTh.removePrefix("คณะ")) },
                        )
                    }
                }
                if (s.faculties.isEmpty()) Text("โหลดรายชื่อคณะไม่ได้ ต่อเน็ตแล้วเปิดหน้านี้ใหม่", color = MaterialTheme.colorScheme.error)
                if (s.showErrors && s.facultyError != null) FieldError(s.facultyError!!)
            }

            // ── แท็ก ──
            if (s.categoryId != null && s.applicableTags.isNotEmpty()) {
                Text("แท็ก (เลือกได้ไม่เกิน $MAX_TAGS)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.applicableTags.forEach { t ->
                        FilterChip(selected = t.id in s.tagIds, onClick = { viewModel.toggleTag(t.id) }, label = { Text(t.nameTh) })
                    }
                }
            }

            if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)
            if (s.showErrors && s.firstError != null) FieldError("กรุณากรอกข้อมูลให้ครบตามที่แจ้งด้านบน")

            // ── ปุ่ม ──
            if (s.isEdit) {
                Button(
                    onClick = { viewModel.submit(finish) },
                    enabled = !s.saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { SubmitLabel(s.saving, "บันทึกการแก้ไข") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveAsDraft(finish) },
                        enabled = !s.saving,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("บันทึกแบบร่าง") }
                    Button(
                        onClick = { viewModel.submit(finish) },
                        enabled = !s.saving,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { SubmitLabel(s.saving, "ลงประกาศ") }
                }
            }
            Box(Modifier.height(8.dp))
        }
    }

    if (pickingCategory) {
        CategoryPickerDialog(
            categories = s.categories,
            onSelect = { viewModel.onCategory(it); pickingCategory = false },
            onDismiss = { pickingCategory = false },
        )
    }

    if (cameraOpen) {
        Dialog(onDismissRequest = { cameraOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ถ่ายรูปสินค้า", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    CameraCapture(
                        useFrontCamera = false,
                        onCaptured = { viewModel.addPhoto(it); cameraOpen = false },
                        onFailure = viewModel::onError,
                    )
                    TextButton(onClick = { cameraOpen = false }) { Text("ปิด") }
                }
            }
        }
    }
}

@Composable
private fun SubmitLabel(saving: Boolean, text: String) {
    if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
    else Text(text, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FieldError(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

@Composable
private fun PhotoTile(bytes: ByteArray, isCover: Boolean, onRemove: () -> Unit) {
    val bitmap = remember(bytes) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
    }
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))) {
        if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (isCover) {
            Text(
                "ปก", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC1B1F1E)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
            Icon(Icons.Outlined.Close, "เอารูปออก", tint = Color.White, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x991B1F1E)).padding(2.dp))
        }
    }
}

/** เลือกหมวดจากต้นไม้ (ลงได้หลายชั้น): แตะหมวดที่มีหมวดย่อยเพื่อลงไปอีกชั้น · แตะหมวดใบสุดท้ายเพื่อเลือก */
@Composable
fun CategoryPickerDialog(categories: List<Category>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var parentId by rememberSaveable { mutableStateOf<Int?>(null) }
    val byId = remember(categories) { categories.associateBy { it.id } }
    val children = remember(categories, parentId) { categories.filter { it.parentId == parentId }.sortedWith(compareBy({ it.sort }, { it.id })) }
    val crumbs = generateSequence(parentId?.let { byId[it] }) { c -> c.parentId?.let { byId[it] } }.toList().reversed()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(if (crumbs.isEmpty()) "เลือกหมวดหมู่" else crumbs.joinToString(" › ") { it.nameTh }, style = MaterialTheme.typography.titleMedium) },
        text = {
            if (children.isEmpty()) {
                Text("ยังไม่มีข้อมูลหมวดหมู่ ต่อเน็ตแล้วลองใหม่")
            } else {
                LazyColumn(Modifier.height(360.dp)) {
                    itemsIndexed(children) { _, c ->
                        val hasChildren = categories.any { it.parentId == c.id }
                        Row(
                            Modifier.fillMaxWidth().clickable { if (hasChildren) parentId = c.id else onSelect(c.id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.nameTh, Modifier.weight(1f))
                            if (hasChildren) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        dismissButton = {
            if (parentId != null) TextButton(onClick = { parentId = byId[parentId]?.parentId }) { Text("ย้อนกลับ") }
        },
    )
}

package com.pixel.swipegaleria

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SortField(val label: String) {
    DATE("Fecha"),
    SIZE("Tamaño")
}

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedSeconds: Long,
    val sizeBytes: Long
)

data class TrashRecord(
    val photo: PhotoItem,
    val markedAtMillis: Long
)

enum class DecisionType {
    KEEP,
    TRASH
}

data class DecisionRecord(
    val photoId: Long,
    val type: DecisionType
)

private const val PREFS_NAME = "swipe_galeria_prefs"
private const val KEY_KEPT_IDS = "kept_ids_json"
private const val KEY_TRASH_ITEMS = "trash_items_json"
private const val KEY_HISTORY_ITEMS = "history_items_json"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SwipeGalleryApp()
            }
        }
    }
}

private fun formatPhotoDate(dateAddedSeconds: Long): String {
    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return formatter.format(Date(TimeUnit.SECONDS.toMillis(dateAddedSeconds)))
}

private fun saveStateToPrefs(
    context: Context,
    keptIds: List<Long>,
    trashList: List<TrashRecord>,
    history: List<DecisionRecord>
) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val keptJson = JSONArray().apply {
        keptIds.forEach { put(it) }
    }
    val trashJson = JSONArray().apply {
        trashList.forEach { record ->
            put(
                JSONObject().apply {
                    put("id", record.photo.id)
                    put("uri", record.photo.uri.toString())
                    put("displayName", record.photo.displayName)
                    put("dateAddedSeconds", record.photo.dateAddedSeconds)
                    put("sizeBytes", record.photo.sizeBytes)
                    put("markedAtMillis", record.markedAtMillis)
                }
            )
        }
    }
    val historyJson = JSONArray().apply {
        history.forEach { item ->
            put(
                JSONObject().apply {
                    put("photoId", item.photoId)
                    put("type", item.type.name)
                }
            )
        }
    }

    prefs.edit()
        .putString(KEY_KEPT_IDS, keptJson.toString())
        .putString(KEY_TRASH_ITEMS, trashJson.toString())
        .putString(KEY_HISTORY_ITEMS, historyJson.toString())
        .apply()
}

private fun loadKeptIdsFromPrefs(context: Context): List<Long> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_KEPT_IDS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                add(arr.getLong(i))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadTrashFromPrefs(context: Context): List<TrashRecord> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_TRASH_ITEMS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getLong("id")
                val uriStr = obj.optString("uri", "")
                val uri = if (uriStr.isNotBlank()) {
                    Uri.parse(uriStr)
                } else {
                    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    ContentUris.withAppendedId(base, id)
                }
                add(
                    TrashRecord(
                        photo = PhotoItem(
                            id = id,
                            uri = uri,
                            displayName = obj.optString("displayName", "IMG_$id.jpg"),
                            dateAddedSeconds = obj.optLong("dateAddedSeconds", 0L),
                            sizeBytes = obj.optLong("sizeBytes", 0L)
                        ),
                        markedAtMillis = obj.optLong("markedAtMillis", System.currentTimeMillis())
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadHistoryFromPrefs(context: Context): List<DecisionRecord> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_HISTORY_ITEMS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = runCatching { DecisionType.valueOf(obj.getString("type")) }.getOrNull()
                if (type != null) {
                    add(DecisionRecord(photoId = obj.getLong("photoId"), type = type))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeGalleryApp() {
    val context = LocalContext.current
    val activity = context as? Activity

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(permission)
        }
    }

    var rawPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var sortField by remember { mutableStateOf(SortField.DATE) }
    var descending by remember { mutableStateOf(true) }

    val keptIds = remember { mutableStateListOf<Long>().apply { addAll(loadKeptIdsFromPrefs(context)) } }
    val trashList = remember { mutableStateListOf<TrashRecord>().apply { addAll(loadTrashFromPrefs(context)) } }
    val history = remember { mutableStateListOf<DecisionRecord>().apply { addAll(loadHistoryFromPrefs(context)) } }

    var pendingDeletionIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showTrashModal by remember { mutableStateOf(false) }

    fun persistNow() {
        saveStateToPrefs(
            context = context,
            keptIds = keptIds.toList(),
            trashList = trashList.toList(),
            history = history.toList()
        )
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingDeletionIds.isNotEmpty()) {
            val idsToRemove = pendingDeletionIds
            trashList.removeAll { it.photo.id in idsToRemove }
            history.removeAll { it.photoId in idsToRemove }
            rawPhotos = rawPhotos.filterNot { it.id in idsToRemove }
            persistNow()
        }
        pendingDeletionIds = emptySet()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            rawPhotos = loadPhotosFromMediaStore(context)
        }
    }

    val sortedPhotos = remember(rawPhotos, sortField, descending) {
        val comparator: Comparator<PhotoItem> = when (sortField) {
            SortField.DATE -> compareBy { it.dateAddedSeconds }
            SortField.SIZE -> compareBy { it.sizeBytes }
        }
        if (descending) rawPhotos.sortedWith(comparator.reversed()) else rawPhotos.sortedWith(comparator)
    }

    val decidedIds = remember(keptIds.size, trashList.size) {
        keptIds.toSet() + trashList.map { it.photo.id }.toSet()
    }

    val pendingPhotos = remember(sortedPhotos, decidedIds) {
        sortedPhotos.filterNot { it.id in decidedIds }
    }

    fun requestSystemDeletion(records: List<TrashRecord>) {
        if (records.isEmpty() || activity == null) return
        val uris = records.map { it.photo.uri }
        pendingDeletionIds = records.map { it.photo.id }.toSet()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            deleteRequestLauncher.launch(request)
        } else {
            val deletedIds = mutableSetOf<Long>()
            records.forEach { rec ->
                try {
                    val rows = context.contentResolver.delete(rec.photo.uri, null, null)
                    if (rows > 0) deletedIds.add(rec.photo.id)
                } catch (_: IntentSender.SendIntentException) {
                } catch (_: SecurityException) {
                }
            }
            if (deletedIds.isNotEmpty()) {
                trashList.removeAll { it.photo.id in deletedIds }
                history.removeAll { it.photoId in deletedIds }
                rawPhotos = rawPhotos.filterNot { it.id in deletedIds }
                persistNow()
            }
            pendingDeletionIds = emptySet()
        }
    }

    LaunchedEffect(trashList.size) {
        val thirtyDaysMillis = TimeUnit.DAYS.toMillis(30)
        val now = System.currentTimeMillis()
        val expiredRecords = trashList.filter { now - it.markedAtMillis >= thirtyDaysMillis }
        if (expiredRecords.isNotEmpty() && pendingDeletionIds.isEmpty()) {
            requestSystemDeletion(expiredRecords)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swipe Galería") },
                actions = {
                    SortField.entries.forEach { field ->
                        val selected = sortField == field
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    descending = !descending
                                } else {
                                    sortField = field
                                    descending = true
                                }
                            },
                            label = { Text(field.label) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val last = history.removeLastOrNull() ?: return@IconButton
                            when (last.type) {
                                DecisionType.KEEP -> keptIds.remove(last.photoId)
                                DecisionType.TRASH -> trashList.removeAll { it.photo.id == last.photoId }
                            }
                            persistNow()
                        },
                        enabled = history.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer")
                    }

                    BadgedBox(
                        badge = {
                            if (trashList.isNotEmpty()) {
                                Badge { Text(trashList.size.toString()) }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        IconButton(onClick = { showTrashModal = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Papelera")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (!hasPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Se requiere acceso a tus fotos para comenzar.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(permission) }) {
                        Text("Conceder permiso")
                    }
                }
            } else {
                val currentPhoto = pendingPhotos.firstOrNull()
                if (currentPhoto == null) {
                    Text("¡Listo! No quedan fotos pendientes por revisar.")
                } else {
                    var offsetX by remember(currentPhoto.id) { mutableFloatStateOf(0f) }
                    var zoomScale by remember(currentPhoto.id) { mutableFloatStateOf(1f) }
                    var panOffset by remember(currentPhoto.id) { mutableStateOf(Offset.Zero) }
                    val currentScaleState by rememberUpdatedState(zoomScale)
                    val swipeThreshold = 220f

                    val trashProgress = (-offsetX / swipeThreshold).coerceIn(0f, 1f)
                    val keepProgress = (offsetX / swipeThreshold).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = if (zoomScale <= 1.02f) offsetX else 0f
                                rotationZ = if (zoomScale <= 1.02f) {
                                    (offsetX / 40f).coerceIn(-12f, 12f)
                                } else {
                                    0f
                                }
                            }
                            .pointerInput(currentPhoto.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (currentScaleState > 1.05f) {
                                            zoomScale = 1f
                                            panOffset = Offset.Zero
                                        } else {
                                            zoomScale = 2f
                                        }
                                    }
                                )
                            }
                            .pointerInput(currentPhoto.id) {
                                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                                    val nextScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                    zoomScale = nextScale
                                    if (nextScale > 1.02f) {
                                        val maxPanX = size.width * (nextScale - 1f) * 0.75f
                                        val maxPanY = size.height * (nextScale - 1f) * 0.75f
                                        panOffset = Offset(
                                            x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                            y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                        )
                                    } else {
                                        panOffset = Offset.Zero
                                    }
                                }
                            }
                            .pointerInput(currentPhoto.id) {
                                detectDragGestures(
                                    onDragEnd = {
                                        if (currentScaleState <= 1.02f) {
                                            when {
                                                offsetX > swipeThreshold -> {
                                                    if (!keptIds.contains(currentPhoto.id)) {
                                                        keptIds.add(currentPhoto.id)
                                                        history.add(
                                                            DecisionRecord(
                                                                photoId = currentPhoto.id,
                                                                type = DecisionType.KEEP
                                                            )
                                                        )
                                                        persistNow()
                                                    }
                                                }
                                                offsetX < -swipeThreshold -> {
                                                    if (trashList.none { it.photo.id == currentPhoto.id }) {
                                                        trashList.add(
                                                            TrashRecord(
                                                                photo = currentPhoto,
                                                                markedAtMillis = System.currentTimeMillis()
                                                            )
                                                        )
                                                        history.add(
                                                            DecisionRecord(
                                                                photoId = currentPhoto.id,
                                                                type = DecisionType.TRASH
                                                            )
                                                        )
                                                        persistNow()
                                                    }
                                                }
                                            }
                                        }
                                        offsetX = 0f
                                    },
                                    onDragCancel = { offsetX = 0f },
                                    onDrag = { change, dragAmount ->
                                        if (currentScaleState <= 1.02f) {
                                            change.consume()
                                            offsetX += dragAmount.x
                                        }
                                    }
                                )
                            }
                    ) {
                        AsyncImage(
                            model = currentPhoto.uri,
                            contentDescription = currentPhoto.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                }
                        )

                        // Capa transparente grande y legible: deslizar + borrador (izquierda) y deslizar + disco (derecha)
                        if (zoomScale <= 1.02f) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center)
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFF3F4F6).copy(alpha = 0.68f + trashProgress * 0.28f),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .graphicsLayer {
                                            val s = 1f + trashProgress * 0.14f
                                            scaleX = s
                                            scaleY = s
                                        }
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SwipeLeft,
                                                contentDescription = "Deslizar a la izquierda para borrar",
                                                tint = Color(0xFF1F2937),
                                                modifier = Modifier.size(34.dp)
                                            )
                                            Icon(
                                                imageVector = Icons.Outlined.AutoFixNormal,
                                                contentDescription = null,
                                                tint = Color(0xFFB91C1C),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Text(
                                            text = "Borrar",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF991B1B),
                                            maxLines = 1
                                        )
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFF3F4F6).copy(alpha = 0.68f + keepProgress * 0.28f),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .graphicsLayer {
                                            val s = 1f + keepProgress * 0.14f
                                            scaleX = s
                                            scaleY = s
                                        }
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SwipeRight,
                                                contentDescription = "Deslizar a la derecha para conservar",
                                                tint = Color(0xFF1F2937),
                                                modifier = Modifier.size(34.dp)
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.Save,
                                                contentDescription = null,
                                                tint = Color(0xFF15803D),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Text(
                                            text = "Guardar",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF166534),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        // Título y fecha de la imagen en la parte inferior
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = currentPhoto.displayName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatPhotoDate(currentPhoto.dateAddedSeconds),
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showTrashModal) {
            TrashManagerModal(
                trashRecords = trashList.toList(),
                onDismiss = { showTrashModal = false },
                onRestoreAll = {
                    val restoredIds = trashList.map { it.photo.id }.toSet()
                    trashList.clear()
                    history.removeAll { it.photoId in restoredIds }
                    persistNow()
                    showTrashModal = false
                },
                onRestoreIds = { idsToRestore ->
                    if (idsToRestore.isNotEmpty()) {
                        trashList.removeAll { it.photo.id in idsToRestore }
                        history.removeAll { it.photoId in idsToRestore }
                        persistNow()
                    }
                },
                onDeletePermanentlyIds = { idsToDelete ->
                    val targets = if (idsToDelete.isNotEmpty()) {
                        trashList.filter { it.photo.id in idsToDelete }
                    } else {
                        trashList.toList()
                    }
                    if (targets.isNotEmpty()) {
                        showTrashModal = false
                        requestSystemDeletion(targets)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashManagerModal(
    trashRecords: List<TrashRecord>,
    onDismiss: () -> Unit,
    onRestoreAll: () -> Unit,
    onRestoreIds: (Set<Long>) -> Unit,
    onDeletePermanentlyIds: (Set<Long>) -> Unit
) {
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(trashRecords) {
        val validIds = trashRecords.map { it.photo.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
    }

    fun toggleId(photoId: Long) {
        selectedIds = if (photoId in selectedIds) {
            selectedIds - photoId
        } else {
            selectedIds + photoId
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Papelera (${trashRecords.size})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                selectedIds = emptySet()
                                onRestoreAll()
                            },
                            enabled = trashRecords.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = "Restaurar todo"
                            )
                        }
                        IconButton(
                            onClick = {
                                val targets = selectedIds
                                selectedIds = emptySet()
                                onDeletePermanentlyIds(targets)
                            },
                            enabled = trashRecords.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Eliminar definitivamente"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (selectedIds.isNotEmpty()) {
                    Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedIds.size} seleccionadas",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    val targets = selectedIds
                                    selectedIds = selectedIds - targets
                                    onRestoreIds(targets)
                                }
                            ) {
                                Text("Recuperar")
                            }
                            Button(
                                onClick = {
                                    val targets = selectedIds
                                    selectedIds = selectedIds - targets
                                    onDeletePermanentlyIds(targets)
                                }
                            ) {
                                Text("Eliminar")
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (trashRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No hay imágenes en la papelera.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    items(trashRecords, key = { it.photo.id }) { record ->
                        val photo = record.photo
                        val isChecked = photo.id in selectedIds

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(
                                    if (isChecked) {
                                        Modifier.border(
                                            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                            MaterialTheme.shapes.small
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { toggleId(photo.id) }
                        ) {
                            AsyncImage(
                                model = photo.uri,
                                contentDescription = photo.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        selectedIds + photo.id
                                    } else {
                                        selectedIds - photo.id
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                            )

                            Text(
                                text = photo.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadPhotosFromMediaStore(context: Context): List<PhotoItem> {
    val result = mutableListOf<PhotoItem>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE
    )

    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val displayName = cursor.getString(nameCol) ?: "IMG_$id.jpg"
            val dateAdded = cursor.getLong(dateCol)
            val sizeBytes = cursor.getLong(sizeCol)
            val uri = ContentUris.withAppendedId(collection, id)
            result.add(
                PhotoItem(
                    id = id,
                    uri = uri,
                    displayName = displayName,
                    dateAddedSeconds = dateAdded,
                    sizeBytes = sizeBytes
                )
            )
        }
    }
    return result
}

package com.pixel.swipegaleria

import android.Manifest
import android.app.Activity
import android.content.ContentUris
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.util.concurrent.TimeUnit

enum class SortField(val label: String) {
    DATE("Fecha"),
    SIZE("Tamaño")
}

data class PhotoItem(
    val id: Long,
    val uri: Uri,
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
    val photo: PhotoItem,
    val type: DecisionType
)

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

    val keptIds = remember { mutableStateListOf<Long>() }
    val trashList = remember { mutableStateListOf<TrashRecord>() }
    val history = remember { mutableStateListOf<DecisionRecord>() }

    var showTrashSheet by remember { mutableStateOf(false) }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val thirtyDaysMillis = TimeUnit.DAYS.toMillis(30)
            val now = System.currentTimeMillis()
            val expiredIds = trashList
                .filter { now - it.markedAtMillis >= thirtyDaysMillis }
                .map { it.photo.id }
                .toSet()

            trashList.removeAll { it.photo.id in expiredIds }
            rawPhotos = rawPhotos.filterNot { it.id in expiredIds }
        }
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

    fun requestSystemDeletion(uris: List<Uri>) {
        if (uris.isEmpty() || activity == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            deleteRequestLauncher.launch(request)
        } else {
            uris.forEach { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: IntentSender.SendIntentException) {
                } catch (_: SecurityException) {
                }
            }
            val deletedSet = uris.toSet()
            trashList.removeAll { it.photo.uri in deletedSet }
            rawPhotos = rawPhotos.filterNot { it.uri in deletedSet }
        }
    }

    LaunchedEffect(trashList.size) {
        val thirtyDaysMillis = TimeUnit.DAYS.toMillis(30)
        val now = System.currentTimeMillis()
        val expiredUris = trashList
            .filter { now - it.markedAtMillis >= thirtyDaysMillis }
            .map { it.photo.uri }
        if (expiredUris.isNotEmpty()) {
            requestSystemDeletion(expiredUris)
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
                                DecisionType.KEEP -> keptIds.remove(last.photo.id)
                                DecisionType.TRASH -> trashList.removeAll { it.photo.id == last.photo.id }
                            }
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
                        IconButton(onClick = { showTrashSheet = true }) {
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
                    val swipeThreshold = 220f

                    AsyncImage(
                        model = currentPhoto.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = offsetX
                                rotationZ = (offsetX / 40f).coerceIn(-12f, 12f)
                            }
                            .pointerInput(currentPhoto.id) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            offsetX > swipeThreshold -> {
                                                keptIds.add(currentPhoto.id)
                                                history.add(DecisionRecord(currentPhoto, DecisionType.KEEP))
                                            }
                                            offsetX < -swipeThreshold -> {
                                                trashList.add(
                                                    TrashRecord(
                                                        photo = currentPhoto,
                                                        markedAtMillis = System.currentTimeMillis()
                                                    )
                                                )
                                                history.add(DecisionRecord(currentPhoto, DecisionType.TRASH))
                                            }
                                        }
                                        offsetX = 0f
                                    },
                                    onDragCancel = { offsetX = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                    }
                                )
                            }
                    )

                    val hint = when {
                        offsetX > 80f -> "CONSERVAR →"
                        offsetX < -80f -> "← PAPELERA"
                        else -> "${sortField.label}: ${if (descending) "Descendente" else "Ascendente"}"
                    }
                    Surface(
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    ) {
                        Text(
                            text = hint,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        if (showTrashSheet) {
            ModalBottomSheet(onDismissRequest = { showTrashSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Papelera (30 días): ${trashList.size} fotos",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val uris = trashList.map { it.photo.uri }
                                showTrashSheet = false
                                requestSystemDeletion(uris)
                            },
                            enabled = trashList.isNotEmpty()
                        ) {
                            Text("Purgar ahora")
                        }
                        OutlinedButton(onClick = { showTrashSheet = false }) {
                            Text("Cerrar")
                        }
                    }
                }
            }
        }
    }
}

private fun loadPhotosFromMediaStore(context: android.content.Context): List<PhotoItem> {
    val result = mutableListOf<PhotoItem>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
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
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val dateAdded = cursor.getLong(dateCol)
            val sizeBytes = cursor.getLong(sizeCol)
            val uri = ContentUris.withAppendedId(collection, id)
            result.add(PhotoItem(id = id, uri = uri, dateAddedSeconds = dateAdded, sizeBytes = sizeBytes))
        }
    }
    return result
}

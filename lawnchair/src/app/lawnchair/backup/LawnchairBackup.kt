package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.GridType
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null

    suspend fun readInfoAndPreview() {
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = BitmapFactory.decodeStream(it) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = BitmapFactory.decodeStream(it) },
            ),
        )
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(4000)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    suspend fun restore(selectedContents: Int): Unit = withContext(Dispatchers.IO) {
        val contents = selectedContents and info.contents
        val staging = if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            Files.createTempDirectory(context.filesDir.toPath(), "restore-").toFile()
        } else {
            null
        }
        try {
            val handlers = mutableMapOf<String, suspend (InputStream) -> Unit>()
            if (staging != null) {
                getFiles(context, forRestore = true).keys.forEach { name ->
                    handlers[name] = { input ->
                        File(staging, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                handlers[WALLPAPER_FILE_NAME] = {
                    WallpaperManager.getInstance(context).setBitmap(BitmapFactory.decodeStream(it))
                }
            }
            readZip(handlers)
            if (staging != null) {
                val layout = File(staging, LAUNCHER_DB_FILE_NAME)
                require(layout.isFile) { "Backup has no launcher database" }
                SQLiteDatabase.openDatabase(layout.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT _id FROM favorites LIMIT 1", null).use { it.moveToFirst() }
                }
                validatedGrid(info)
                File(staging, INFO_FILE_NAME).outputStream().use { info.writeTo(it) }
                val pending = pendingRestore(context)
                check(!pending.exists() || pending.deleteRecursively()) { "Unable to replace the pending backup" }
                Files.move(staging.toPath(), pending.toPath(), ATOMIC_MOVE)
            }
        } finally {
            staging?.deleteRecursively()
        }
    }

    private suspend fun readZip(handlers: Map<String, suspend (InputStream) -> Unit>) {
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
            pfd.use {
                FileInputStream(it.fileDescriptor).use { inStream ->
                    ZipInputStream(inStream).use { zipIs ->
                        var entry: ZipEntry?
                        while (true) {
                            entry = zipIs.nextEntry
                            if (entry == null) break
                            handlers[entry.name]?.invoke(zipIs)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val BACKUP_VERSION = 1
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"
        const val NATIVE_RESTORED_DB_FILE_NAME = "native-restored.db"
        private const val PENDING_RESTORE_DIR = "pending-native-restore"
        private const val RESTORE_PREFS_NAME = "lawnchair-native-restore"
        private const val WORKSPACE_COLUMNS = "pref_workspaceColumns"
        private const val WORKSPACE_ROWS = "pref_workspaceRows"
        private const val DOCK_COLUMNS = "pref_hotseatColumns"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
        )

        private fun pendingRestore(context: Context) = File(context.filesDir, PENDING_RESTORE_DIR)

        private fun validatedGrid(info: BackupInfo): DeviceGridState {
            val grid = DeviceGridState(info.gridState)
            require(grid.columns in 1..20 && grid.rows in 1..20 && grid.numHotseat in 1..40) {
                "Backup has invalid grid dimensions"
            }
            return grid
        }

        /** Apply the archive before the new process initializes its preferences or launcher model. */
        @Suppress("DEPRECATION")
        fun installPendingRestore(context: Context) {
            val pending = pendingRestore(context)
            if (!pending.isDirectory) return
            try {
                val info = File(pending, INFO_FILE_NAME).inputStream().use { BackupInfo.parseFrom(it) }
                val grid = validatedGrid(info)
                val savedPrefs = File(pending, PREFS_FILE_NAME)
                val scratch = File(prefsFile(context).parentFile, "$RESTORE_PREFS_NAME.xml")
                context.deleteSharedPreferences(RESTORE_PREFS_NAME)
                val restoredPrefs = if (savedPrefs.isFile) {
                    savedPrefs.copyTo(scratch, overwrite = true)
                    context.getSharedPreferences(RESTORE_PREFS_NAME, Context.MODE_PRIVATE).all
                } else {
                    null
                }
                val foldedDockCount = (restoredPrefs?.get(DOCK_COLUMNS) as? Int)
                    ?.takeIf { it in 1..20 } ?: grid.numHotseat
                val gridInfo = DeviceProfileOverrides.DBGridInfo(foldedDockCount, grid.rows, grid.columns)
                val settingsDb = File(pending, PREFS_DB_FILE_NAME)
                if (settingsDb.isFile) copyRestoredFile(settingsDb, prefsDbFile(context), isDatabase = true)
                val datastore = File(pending, PREFS_DATASTORE_FILE_NAME)
                if (datastore.isFile) copyRestoredFile(datastore, prefsDataStoreFile(context))
                val prefs = LauncherPrefs.getPrefs(context)
                val editor = prefs.edit()
                if (restoredPrefs != null) {
                    editor.clear()
                    val launcherPrefs = LauncherPrefs(context.applicationContext)
                    with(launcherPrefs) {
                        restoredPrefs.forEach { (key, value) ->
                            if (value != null) editor.putValue(LauncherPrefs.backedUpItem(key, value), value)
                        }
                    }
                }
                val restoredLayout = context.getDatabasePath(NATIVE_RESTORED_DB_FILE_NAME)
                copyRestoredFile(File(pending, LAUNCHER_DB_FILE_NAME), restoredLayout)
                val restoredGridType = restoredPrefs?.get(DeviceGridState.KEY_GRID_TYPE) as? Int
                    ?: GridType.GRID_TYPE_ANY
                val saved = editor
                    .putInt(WORKSPACE_COLUMNS, gridInfo.numColumns)
                    .putInt(WORKSPACE_ROWS, gridInfo.numRows)
                    .putInt(DOCK_COLUMNS, gridInfo.numHotseatColumns)
                    .putString(DeviceGridState.KEY_WORKSPACE_SIZE, info.gridState.gridSize)
                    .putInt(DeviceGridState.KEY_HOTSEAT_COUNT, grid.numHotseat)
                    .putInt(DeviceGridState.KEY_DEVICE_TYPE, grid.deviceType)
                    .putString(DeviceGridState.KEY_DB_FILE, gridInfo.dbFile)
                    .putInt(DeviceGridState.KEY_GRID_TYPE, restoredGridType)
                    .remove(RestoreDbTask.RESTORED_DEVICE_TYPE)
                    .commit()
                if (!saved) {
                    Files.deleteIfExists(restoredLayout.toPath())
                    error("Unable to save restored grid preferences")
                }
                pending.deleteRecursively()
            } finally {
                context.deleteSharedPreferences(RESTORE_PREFS_NAME)
            }
        }

        private fun copyRestoredFile(source: File, destination: File, isDatabase: Boolean = false) {
            val parent = checkNotNull(destination.parentFile)
            parent.mkdirs()
            val staged = Files.createTempFile(parent.toPath(), "restore-", ".tmp")
            try {
                Files.copy(source.toPath(), staged, REPLACE_EXISTING)
                if (isDatabase) {
                    for (suffix in listOf("-journal", "-wal", "-shm")) {
                        Files.deleteIfExists(File(parent, destination.name + suffix).toPath())
                    }
                }
                Files.move(staged, destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staged)
            }
        }

        fun generateBackupFileName(): String {
            val fileName = "Lawnchair_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            return mapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
        }

        @SuppressLint("MissingPermission")
        suspend fun create(context: Context, contents: Int, screenshotBitmap: Bitmap, fileUri: Uri) {
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText = (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
            val info = BackupInfo.newBuilder()
                .setLawnchairVersion(BuildConfig.VERSION_CODE)
                .setBackupVersion(BACKUP_VERSION)
                .setCreatedAt(createdAt)
                .setContents(contents)
                .setGridState(DeviceGridState(idp).toProtoMessage())
                .setPreviewWidth(screenshotBitmap.width)
                .setPreviewHeight(screenshotBitmap.height)
                .setPreviewDarkText(wallpaperSupportsDarkText)
                .build()

            val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")!!
            withContext(Dispatchers.IO) {
                pfd.use {
                    ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                        out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                        info.writeTo(out)

                        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            val wallpaperBitmap = wallpaperManager.drawable?.toBitmap()
                            if (wallpaperBitmap != null) {
                                out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                wallpaperBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                            screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }

                        getFiles(context, forRestore = false).entries.forEach {
                            if (!it.value.exists()) return@forEach
                            out.putNextEntry(ZipEntry(it.key))
                            it.value.inputStream().use { input -> input.copyTo(out) }
                        }
                    }
                }
            }
        }

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName = if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
            return context.getDatabasePath(dbName)
        }

        private fun prefsFile(context: Context): File {
            val dir = context.cacheDir.parent
            return File(dir, "shared_prefs/$PREFS_FILE_NAME")
        }

        private fun prefsDbFile(context: Context): File {
            return context.getDatabasePath(PREFS_DB_FILE_NAME)
        }

        private fun prefsDataStoreFile(context: Context): File {
            return File(context.filesDir, "datastore/${PREFS_DATASTORE_FILE_NAME}")
        }
    }
}

package com.foton.crawlermetraj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class PoleMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.foton.crawlermetraj.POLE_START_MONITOR"
        const val ACTION_STOP = "com.foton.crawlermetraj.POLE_STOP_MONITOR"
        const val EXTRA_FOLDER_URI = "pole_folder_uri"

        private const val CHANNEL_ID = "foton_pole_monitor"
        private const val NOTIFICATION_ID = 1517
        private const val POLL_MS = 10_000L
        private const val REQUIRED_STABLE_POLLS = 2
    }

    private data class FileState(val size: Long, val stablePolls: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val fileStates = mutableMapOf<String, FileState>()
    private lateinit var analyzer: PoleAnalyzer

    override fun onCreate() {
        super.onCreate()
        analyzer = PoleAnalyzer(this)
        PoleStore.ensureVersion(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring("Pole takibi durduruldu")
            return START_NOT_STICKY
        }

        val saved = PoleStore.getMonitorConfig(this)
        val folderText = intent?.getStringExtra(EXTRA_FOLDER_URI) ?: saved.folderUri
        if (folderText.isNullOrBlank()) {
            stopMonitoring("Pole takibi için klasör eksik")
            return START_NOT_STICKY
        }

        val folderUri = Uri.parse(folderText)
        PoleStore.setActiveFolder(this, folderText)
        PoleStore.saveMonitorConfig(this, true, folderText, "Pole otomatik takip başlatıldı")
        startForeground(NOTIFICATION_ID, buildNotification("Klasör izleniyor"))

        monitorJob?.cancel()
        fileStates.clear()
        monitorJob = scope.launch { monitorLoop(folderUri) }
        return START_STICKY
    }

    private suspend fun monitorLoop(folderUri: Uri) {
        while (scope.isActive) {
            try {
                val root = DocumentFile.fromTreeUri(this, folderUri)
                if (root == null || !root.exists()) {
                    updateStatus("Pole klasörüne erişilemiyor")
                    delay(POLL_MS)
                    continue
                }

                val media = PoleFileUtils.collect(root)
                var waiting = 0
                var processed = 0

                for (entry in media) {
                    val file = entry.file
                    val uriText = file.uri.toString()
                    if (PoleStore.isProcessed(this, uriText, folderUri.toString())) continue

                    val size = file.length()
                    val previous = fileStates[uriText]
                    if (size <= 0L) {
                        fileStates[uriText] = FileState(size, 0)
                        waiting++
                        continue
                    }
                    if (previous == null || previous.size != size) {
                        fileStates[uriText] = FileState(size, 0)
                        waiting++
                        continue
                    }
                    val stable = previous.stablePolls + 1
                    if (stable < REQUIRED_STABLE_POLLS) {
                        fileStates[uriText] = FileState(size, stable)
                        waiting++
                        continue
                    }

                    updateStatus("İşleniyor: ${file.name ?: "dosya"}")
                    when (entry.kind) {
                        PoleFileUtils.Kind.VIDEO -> PoleStore.upsertVideo(
                            this,
                            analyzer.analyzeVideo(file.uri, file.name ?: "video"),
                            folderUri.toString()
                        )
                        PoleFileUtils.Kind.IMAGE -> PoleStore.upsertPhoto(
                            this,
                            analyzer.analyzePhoto(file.uri, file.name ?: "foto"),
                            folderUri.toString()
                        )
                    }

                    val current = PoleStore.getMonitorConfig(this)
                    if (!current.active || current.folderUri != folderUri.toString()) return

                    fileStates.remove(uriText)
                    processed++
                }

                PoleStore.reconcile(this, folderUri.toString())
                val records = PoleStore.getRecords(this, folderUri.toString())
                val matched = records.count { it.isMatched }
                val pendingVideos = records.count { !it.isMatched }
                val pendingPhotos = PoleStore.getPendingPhotos(this, folderUri.toString()).size
                val total = records.mapNotNull { it.distanceM }.sum()
                val status = when {
                    processed > 0 -> "$processed yeni dosya işlendi"
                    waiting > 0 -> "$waiting dosya tamamlanması bekleniyor"
                    pendingVideos > 0 || pendingPhotos > 0 -> "Eşleşme bekleniyor"
                    else -> "Yeni video / fotoğraf bekleniyor"
                }
                updateStatus(
                    "$status • $matched eşleşme • ${String.format(Locale("tr", "TR"), "%.2f", total)} m"
                )
            } catch (e: Exception) {
                updateStatus("Pole takip hatası: ${e.message ?: "bilinmeyen hata"}")
            }
            delay(POLL_MS)
        }
    }

    private fun updateStatus(status: String) {
        PoleStore.setMonitorStatus(this, true, status)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(status)
        )
    }

    private fun stopMonitoring(status: String) {
        monitorJob?.cancel()
        monitorJob = null
        fileStates.clear()
        PoleStore.setMonitorStatus(this, false, status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FOTON Pole Kamera Takibi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pole kamera klasöründeki video ve fotoğrafları eşleştirir"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(detail: String): Notification {
        val openIntent = Intent(this, PoleActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 21, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, PoleMonitorService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 22, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FOTON Pole Kamera")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopPending)
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        analyzer.close()
        super.onDestroy()
    }
}

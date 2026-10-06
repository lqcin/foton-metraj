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

class FolderMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.foton.crawlermetraj.START_MONITOR"
        const val ACTION_STOP = "com.foton.crawlermetraj.STOP_MONITOR"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_FIRMA = "firma"
        const val EXTRA_TARIH = "tarih"

        private const val CHANNEL_ID = "foton_metraj_monitor"
        private const val NOTIFICATION_ID = 1506
        private const val POLL_MS = 10_000L
        private const val REQUIRED_STABLE_POLLS = 2
    }

    private data class FileState(val size: Long, val stablePolls: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val fileStates = mutableMapOf<String, FileState>()
    private val failedThisSession = mutableSetOf<String>()
    private lateinit var analyzer: VideoAnalyzer

    override fun onCreate() {
        super.onCreate()
        analyzer = VideoAnalyzer(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring("Otomatik takip durduruldu")
            return START_NOT_STICKY
        }

        val saved = ResultStore.getMonitorConfig(this)
        val folderText = intent?.getStringExtra(EXTRA_FOLDER_URI) ?: saved.folderUri
        val firma = intent?.getStringExtra(EXTRA_FIRMA) ?: saved.firma
        val tarih = intent?.getStringExtra(EXTRA_TARIH) ?: saved.tarih

        if (folderText.isNullOrBlank() || firma.isNullOrBlank() || tarih.isNullOrBlank()) {
            stopMonitoring("Takip için klasör/firma/tarih eksik")
            return START_NOT_STICKY
        }

        val folderUri = Uri.parse(folderText)
        ResultStore.saveMonitorConfig(
            this,
            active = true,
            folderUri = folderText,
            firma = firma,
            tarih = tarih,
            status = "Otomatik takip başlatıldı"
        )
        startForeground(NOTIFICATION_ID, buildNotification(firma, tarih, "Klasör izleniyor"))

        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch { monitorLoop(folderUri, firma, tarih) }
        }
        return START_STICKY
    }

    private suspend fun monitorLoop(folderUri: Uri, firma: String, tarih: String) {
        while (scope.isActive) {
            try {
                val root = DocumentFile.fromTreeUri(this, folderUri)
                if (root == null || !root.exists()) {
                    updateStatus(firma, tarih, "Klasöre erişilemiyor")
                    delay(POLL_MS)
                    continue
                }

                val folderName = root.name
                val videos = VideoFileUtils.collectVideos(root)
                var waiting = 0
                var justProcessed = 0

                for (video in videos) {
                    if (ResultStore.isProcessed(this, firma, tarih, video.uri)) continue

                    val key = video.uri.toString()
                    if (key in failedThisSession) continue
                    val size = video.length()
                    val previous = fileStates[key]

                    if (size <= 0L) {
                        fileStates[key] = FileState(size, 0)
                        waiting++
                        continue
                    }

                    if (previous == null || previous.size != size) {
                        fileStates[key] = FileState(size, 0)
                        waiting++
                        continue
                    }

                    val stable = previous.stablePolls + 1
                    if (stable < REQUIRED_STABLE_POLLS) {
                        fileStates[key] = FileState(size, stable)
                        waiting++
                        continue
                    }

                    updateStatus(firma, tarih, "Analiz ediliyor: ${video.name ?: "video"}")
                    val result = analyzer.analyze(video.uri, video.name ?: "video", folderName)
                    ResultStore.addResult(this, firma, tarih, video.uri, result)
                    fileStates.remove(key)
                    if (result.isSuccess) {
                        justProcessed++
                    } else {
                        failedThisSession.add(key)
                    }
                }

                val rows = ResultStore.getResults(this, firma, tarih)
                val total = rows.mapNotNull { it.metraj }.sum()
                val status = when {
                    justProcessed > 0 -> "$justProcessed yeni video işlendi"
                    waiting > 0 -> "$waiting video tamamlanması bekleniyor"
                    else -> "Yeni video bekleniyor"
                }
                updateStatus(firma, tarih, status, rows.size, total)
            } catch (e: Exception) {
                updateStatus(firma, tarih, "Takip hatası: ${e.message ?: "bilinmeyen hata"}")
            }
            delay(POLL_MS)
        }
    }

    private fun updateStatus(
        firma: String,
        tarih: String,
        status: String,
        count: Int? = null,
        total: Double? = null
    ) {
        ResultStore.setMonitorStatus(this, true, status)
        val detail = if (count != null && total != null) {
            "$status • $count video • ${String.format(Locale("tr", "TR"), "%.2f", total)} m"
        } else status
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(firma, tarih, detail))
    }

    private fun stopMonitoring(status: String) {
        monitorJob?.cancel()
        monitorJob = null
        fileStates.clear()
        failedThisSession.clear()
        ResultStore.setMonitorStatus(this, false, status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FOTON Metraj Takibi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Seçilen crawler video klasöründeki yeni videoları izler"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(firma: String, tarih: String, detail: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, FolderMonitorService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 11, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FOTON Metraj Takibi • $firma")
            .setContentText("$tarih • $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$tarih • $detail"))
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

package com.foton.crawlermetraj

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var editFirma: EditText
    private lateinit var editTarih: EditText
    private lateinit var buttonFolder: Button
    private lateinit var buttonAnalyze: Button
    private lateinit var buttonMonitor: Button
    private lateinit var buttonExcel: Button
    private lateinit var buttonShare: Button
    private lateinit var textFolder: TextView
    private lateinit var textStatus: TextView
    private lateinit var textTotal: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultsContainer: LinearLayout

    private lateinit var analyzer: VideoAnalyzer
    private var selectedFolderUri: Uri? = null
    private var results: List<VideoResult> = emptyList()
    private var pendingXlsx: ByteArray? = null
    private var lastExcelUri: Uri? = null
    private var pendingMonitorStart = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastUiSignature = ""
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStoredResults()
            uiHandler.postDelayed(this, 2500L)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selectedFolderUri = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            getPreferences(MODE_PRIVATE).edit().putString("last_folder", uri.toString()).apply()
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            textFolder.text = name
            textStatus.text = "Klasör hazır"
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingMonitorStart) {
            pendingMonitorStart = false
            if (granted) startMonitorInternal()
            else Toast.makeText(this, "Bildirim izni olmadan arka plan takibi güvenilir çalışmaz", Toast.LENGTH_LONG).show()
        }
    }

    private val createExcel = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            val data = pendingXlsx
            if (data != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    lastExcelUri = uri
                    buttonShare.isEnabled = true
                    textStatus.text = "Excel oluşturuldu"
                    Toast.makeText(this, "Excel kaydedildi", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Excel kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingXlsx = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = VideoAnalyzer(this)

        editFirma = findViewById(R.id.editFirma)
        editTarih = findViewById(R.id.editTarih)
        buttonFolder = findViewById(R.id.buttonFolder)
        buttonAnalyze = findViewById(R.id.buttonAnalyze)
        buttonMonitor = findViewById(R.id.buttonMonitor)
        buttonExcel = findViewById(R.id.buttonExcel)
        buttonShare = findViewById(R.id.buttonShare)
        textFolder = findViewById(R.id.textFolder)
        textStatus = findViewById(R.id.textStatus)
        textTotal = findViewById(R.id.textTotal)
        progress = findViewById(R.id.progress)
        resultsContainer = findViewById(R.id.resultsContainer)

        editTarih.setText(SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).format(Date()))

        val prefs = getPreferences(MODE_PRIVATE)
        editFirma.setText(prefs.getString("last_firma", "") ?: "")
        prefs.getString("last_folder", null)?.let {
            selectedFolderUri = Uri.parse(it)
            textFolder.text = DocumentFile.fromTreeUri(this, selectedFolderUri!!)?.name ?: it
        }

        buttonFolder.setOnClickListener { folderPicker.launch(selectedFolderUri) }
        buttonAnalyze.setOnClickListener { startBatchAnalysis() }
        buttonMonitor.setOnClickListener { toggleMonitoring() }
        buttonExcel.setOnClickListener { exportExcel() }
        buttonShare.setOnClickListener { shareExcel() }

        refreshStoredResults(force = true)
    }

    private fun validateInputs(): Triple<Uri, String, String>? {
        val folderUri = selectedFolderUri
        if (folderUri == null) {
            Toast.makeText(this, "Önce video klasörünü seç", Toast.LENGTH_SHORT).show()
            return null
        }
        val firma = editFirma.text.toString().trim()
        if (firma.isBlank()) {
            editFirma.error = "Firma adını yaz"
            return null
        }
        val tarih = editTarih.text.toString().trim()
        if (tarih.isBlank()) {
            editTarih.error = "Tarihi yaz"
            return null
        }
        getPreferences(MODE_PRIVATE).edit().putString("last_firma", firma).apply()
        return Triple(folderUri, firma, tarih)
    }

    private fun startBatchAnalysis() {
        if (ResultStore.getMonitorConfig(this).active) {
            Toast.makeText(this, "Toplu analizden önce Otomatik Takip'i durdur", Toast.LENGTH_LONG).show()
            return
        }
        val (folderUri, firma, tarih) = validateInputs() ?: return

        setBusy(true)
        lastExcelUri = null
        buttonShare.isEnabled = false
        progress.progress = 0

        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(this@MainActivity, folderUri)
            }
            val folderName = root?.name
            val videos = withContext(Dispatchers.IO) {
                root?.let { VideoFileUtils.collectVideos(it) } ?: emptyList()
            }

            if (videos.isEmpty()) {
                textStatus.text = "Bu klasörde desteklenen video bulunamadı"
                setBusy(false)
                return@launch
            }

            progress.max = videos.size
            var analyzedNow = 0
            var skipped = 0

            videos.forEachIndexed { index, video ->
                if (ResultStore.isProcessed(this@MainActivity, firma, tarih, video.uri)) {
                    skipped++
                } else {
                    textStatus.text = "${index + 1}/${videos.size} analiz ediliyor: ${video.name ?: "video"}"
                    val result = analyzer.analyze(video.uri, video.name ?: "video", folderName)
                    ResultStore.addResult(this@MainActivity, firma, tarih, video.uri, result)
                    analyzedNow++
                }
                progress.progress = index + 1
                refreshStoredResults(force = true)
            }

            textStatus.text = "Tamamlandı: $analyzedNow yeni video işlendi${if (skipped > 0) ", $skipped daha önce işlenmiş" else ""}"
            setBusy(false)
            refreshStoredResults(force = true)
        }
    }

    private fun toggleMonitoring() {
        val config = ResultStore.getMonitorConfig(this)
        if (config.active) {
            val stopIntent = Intent(this, FolderMonitorService::class.java).apply {
                action = FolderMonitorService.ACTION_STOP
            }
            startService(stopIntent)
            ResultStore.setMonitorStatus(this, false, "Otomatik takip durduruluyor")
            refreshStoredResults(force = true)
            return
        }

        if (validateInputs() == null) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingMonitorStart = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startMonitorInternal()
    }

    private fun startMonitorInternal() {
        val (folderUri, firma, tarih) = validateInputs() ?: return
        val intent = Intent(this, FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_START
            putExtra(FolderMonitorService.EXTRA_FOLDER_URI, folderUri.toString())
            putExtra(FolderMonitorService.EXTRA_FIRMA, firma)
            putExtra(FolderMonitorService.EXTRA_TARIH, tarih)
        }
        ContextCompat.startForegroundService(this, intent)
        ResultStore.saveMonitorConfig(
            this,
            active = true,
            folderUri = folderUri.toString(),
            firma = firma,
            tarih = tarih,
            status = "Otomatik takip başlatılıyor"
        )
        refreshStoredResults(force = true)
    }

    private fun refreshStoredResults(force: Boolean = false) {
        if (!::editFirma.isInitialized) return
        val firma = editFirma.text.toString().trim()
        val tarih = editTarih.text.toString().trim()
        val config = ResultStore.getMonitorConfig(this)
        val stored = if (firma.isNotBlank() && tarih.isNotBlank()) {
            ResultStore.getResults(this, firma, tarih)
        } else emptyList()

        val total = stored.mapNotNull { it.metraj }.sum()
        val signature = "$firma|$tarih|${stored.size}|$total|${config.active}|${config.status}"
        if (!force && signature == lastUiSignature) return
        lastUiSignature = signature

        results = stored
        resultsContainer.removeAllViews()
        stored.forEach { appendResult(it) }
        updateTotal(stored)
        buttonExcel.isEnabled = stored.any { it.isSuccess }
        buttonMonitor.text = if (config.active) "OTOMATİK TAKİBİ DURDUR" else "OTOMATİK TAKİBİ BAŞLAT"
        buttonAnalyze.isEnabled = !config.active
        if (config.active || config.status.startsWith("Otomatik")) {
            textStatus.text = config.status
        }
    }

    private fun appendResult(result: VideoResult) {
        val tr = Locale("tr", "TR")
        val rawFirst = result.firstMeter?.let { String.format(tr, "%.2f", it) } ?: "?"
        val effectiveFirst = result.effectiveFirstMeter?.let { String.format(tr, "%.2f", it) } ?: "?"
        val last = result.lastMeter?.let { String.format(tr, "%.2f", it) } ?: "?"
        val meter = result.metraj?.let { String.format(tr, "%.2f m", it) } ?: "OKUNAMADI"
        val parsel = result.header.parsel ?: "?"
        val hat = result.header.konum ?: "?"
        val cap = result.header.capMm?.let { "Ø$it" } ?: "Ø?"
        val yon = result.header.yon ?: "?"

        val line = TextView(this).apply {
            text = "$parsel Parsel | $hat | $cap | $yon\n10.sn: $rawFirst   Başlangıç: $effectiveFirst   Son: $last   →   $meter\n${result.fileName}"
            textSize = 15f
            setTextColor(if (result.isSuccess) Color.rgb(31, 65, 81) else Color.rgb(177, 76, 50))
            setPadding(12, 12, 12, 12)
        }
        resultsContainer.addView(line)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.rgb(220, 224, 230))
        }
        resultsContainer.addView(divider)
    }

    private fun updateTotal(values: List<VideoResult>) {
        val total = values.mapNotNull { it.metraj }.sum()
        textTotal.text = "TOPLAM: ${String.format(Locale("tr", "TR"), "%.2f", total)} m"
    }

    private fun exportExcel() {
        if (results.isEmpty()) return
        val firma = editFirma.text.toString().trim().ifBlank { "Firma" }
        val tarih = editTarih.text.toString().trim().ifBlank { "Tarih" }

        lifecycleScope.launch {
            textStatus.text = "Excel hazırlanıyor"
            val bytes = withContext(Dispatchers.Default) {
                XlsxExporter.build(firma, tarih, results)
            }
            pendingXlsx = bytes
            val safeFirma = firma.replace(Regex("[^A-Za-z0-9ÇĞİÖŞÜçğıöşü_-]"), "_")
            val safeDate = tarih.replace('.', '-')
            createExcel.launch("FOTON_${safeFirma}_${safeDate}_Metraj.xlsx")
        }
    }

    private fun shareExcel() {
        val uri = lastExcelUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Excel'i paylaş"))
    }

    private fun setBusy(busy: Boolean) {
        buttonFolder.isEnabled = !busy
        buttonAnalyze.isEnabled = !busy && !ResultStore.getMonitorConfig(this).active
        buttonMonitor.isEnabled = !busy
        editFirma.isEnabled = !busy
        editTarih.isEnabled = !busy
        if (busy) {
            buttonExcel.isEnabled = false
            buttonShare.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        analyzer.close()
        super.onDestroy()
    }
}

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

class PoleActivity : AppCompatActivity() {

    private lateinit var buttonFolder: Button
    private lateinit var buttonMonitor: Button
    private lateinit var buttonStop: Button
    private lateinit var buttonScan: Button
    private lateinit var buttonChangeFolder: Button
    private lateinit var buttonExcel: Button
    private lateinit var buttonShare: Button
    private lateinit var buttonCrawler: Button
    private lateinit var textFolder: TextView
    private lateinit var textStatus: TextView
    private lateinit var textTotal: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultsContainer: LinearLayout

    private lateinit var analyzer: PoleAnalyzer
    private var selectedFolderUri: Uri? = null
    private var records: List<PoleRecord> = emptyList()
    private var pendingXlsx: ByteArray? = null
    private var lastExcelUri: Uri? = null
    private var pendingMonitorStart = false
    private var lastUiSignature = ""

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshResults()
            uiHandler.postDelayed(this, 2500L)
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selectedFolderUri = uri
            PoleStore.setActiveFolder(this, uri.toString())
            lastUiSignature = ""
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            getSharedPreferences("pole_ui", MODE_PRIVATE).edit().putString("last_folder", uri.toString()).apply()
            textFolder.text = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            textStatus.text = "Pole klasörü hazır • bu klasör bağımsız tutulur"
            refreshResults(force = true)
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
                    textStatus.text = "Pole Excel oluşturuldu"
                } catch (e: Exception) {
                    Toast.makeText(this, "Excel kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingXlsx = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pole)
        analyzer = PoleAnalyzer(this)
        PoleStore.ensureVersion(this)

        buttonFolder = findViewById(R.id.poleButtonFolder)
        buttonMonitor = findViewById(R.id.poleButtonMonitor)
        buttonStop = findViewById(R.id.poleButtonStop)
        buttonScan = findViewById(R.id.poleButtonScan)
        buttonChangeFolder = findViewById(R.id.poleButtonChangeFolder)
        buttonExcel = findViewById(R.id.poleButtonExcel)
        buttonShare = findViewById(R.id.poleButtonShare)
        buttonCrawler = findViewById(R.id.poleButtonCrawler)
        textFolder = findViewById(R.id.poleTextFolder)
        textStatus = findViewById(R.id.poleTextStatus)
        textTotal = findViewById(R.id.poleTextTotal)
        progress = findViewById(R.id.poleProgress)
        resultsContainer = findViewById(R.id.poleResultsContainer)

        val prefs = getSharedPreferences("pole_ui", MODE_PRIVATE)
        prefs.getString("last_folder", null)?.let {
            selectedFolderUri = Uri.parse(it)
            PoleStore.setActiveFolder(this, it)
            textFolder.text = DocumentFile.fromTreeUri(this, selectedFolderUri!!)?.name ?: it
        }

        buttonFolder.setOnClickListener { folderPicker.launch(selectedFolderUri) }
        buttonMonitor.setOnClickListener { startMonitoringRequested() }
        buttonStop.setOnClickListener { stopMonitoring() }
        buttonScan.setOnClickListener { startBatchScan() }
        buttonChangeFolder.setOnClickListener { prepareNewFolder() }
        buttonExcel.setOnClickListener { exportExcel() }
        buttonShare.setOnClickListener { shareExcel() }
        buttonCrawler.setOnClickListener { finish() }

        refreshResults(force = true)
    }

    private fun validateFolder(): Uri? {
        val uri = selectedFolderUri
        if (uri == null) {
            Toast.makeText(this, "Önce Pole video/fotoğraf klasörünü seç", Toast.LENGTH_SHORT).show()
            return null
        }
        PoleStore.setActiveFolder(this, uri.toString())
        return uri
    }

    private fun startBatchScan() {
        if (PoleStore.getMonitorConfig(this).active) {
            Toast.makeText(this, "Toplu taramadan önce Pole takibini durdur", Toast.LENGTH_LONG).show()
            return
        }
        val folderUri = validateFolder() ?: return
        setBusy(true)
        progress.progress = 0
        lastExcelUri = null
        buttonShare.isEnabled = false

        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(this@PoleActivity, folderUri) }
            val media = withContext(Dispatchers.IO) { root?.let { PoleFileUtils.collect(it) } ?: emptyList() }
            if (media.isEmpty()) {
                textStatus.text = "Bu klasörde video veya fotoğraf bulunamadı"
                setBusy(false)
                return@launch
            }

            // Önce videoları, sonra fotoğrafları işleriz. Böylece toplu taramada eşleşmeler hemen oluşur.
            val ordered = media.sortedBy { if (it.kind == PoleFileUtils.Kind.VIDEO) 0 else 1 }
            progress.max = ordered.size
            var processed = 0
            var skipped = 0

            ordered.forEachIndexed { index, entry ->
                val file = entry.file
                val uriText = file.uri.toString()
                if (PoleStore.isProcessed(this@PoleActivity, uriText, folderUri.toString())) {
                    skipped++
                } else {
                    textStatus.text = "${index + 1}/${ordered.size} işleniyor: ${file.name ?: "dosya"}"
                    when (entry.kind) {
                        PoleFileUtils.Kind.VIDEO -> PoleStore.upsertVideo(
                            this@PoleActivity,
                            analyzer.analyzeVideo(file.uri, file.name ?: "video"),
                            folderUri.toString()
                        )
                        PoleFileUtils.Kind.IMAGE -> PoleStore.upsertPhoto(
                            this@PoleActivity,
                            analyzer.analyzePhoto(file.uri, file.name ?: "foto"),
                            folderUri.toString()
                        )
                    }
                    processed++
                }
                progress.progress = index + 1
                refreshResults(force = true)
            }

            PoleStore.reconcile(this@PoleActivity, folderUri.toString())
            textStatus.text = "Tarama tamamlandı: $processed yeni dosya${if (skipped > 0) ", $skipped daha önce işlenmiş" else ""}"
            setBusy(false)
            refreshResults(force = true)
        }
    }

    private fun startMonitoringRequested() {
        if (PoleStore.getMonitorConfig(this).active) {
            Toast.makeText(this, "Pole otomatik takip zaten çalışıyor", Toast.LENGTH_SHORT).show()
            return
        }
        if (validateFolder() == null) return
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
        val folderUri = validateFolder() ?: return
        val intent = Intent(this, PoleMonitorService::class.java).apply {
            action = PoleMonitorService.ACTION_START
            putExtra(PoleMonitorService.EXTRA_FOLDER_URI, folderUri.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        PoleStore.setActiveFolder(this, folderUri.toString())
        PoleStore.saveMonitorConfig(this, true, folderUri.toString(), "Pole otomatik takip başlatılıyor")
        refreshResults(force = true)
    }

    private fun stopMonitoring(status: String = "Pole otomatik takip durduruldu") {
        val cfg = PoleStore.getMonitorConfig(this)
        if (cfg.active) {
            startService(Intent(this, PoleMonitorService::class.java).apply { action = PoleMonitorService.ACTION_STOP })
        }
        PoleStore.setMonitorStatus(this, false, status)
        refreshResults(force = true)
    }

    private fun prepareNewFolder() {
        stopMonitoring("Yeni Pole klasörü için takip durduruldu")
        selectedFolderUri = null
        getSharedPreferences("pole_ui", MODE_PRIVATE).edit().remove("last_folder").apply()
        // Önceki klasörün verisi silinmez. Her klasör kendi bağımsız kayıt havuzunu korur.
        PoleStore.setActiveFolder(this, null)
        records = emptyList()
        lastUiSignature = ""
        resultsContainer.removeAllViews()
        textFolder.text = "Klasör seçilmedi"
        textStatus.text = "Yeni Pole klasörünü seç"
        textTotal.text = "TOPLAM: 0,00 m"
        progress.progress = 0
        buttonExcel.isEnabled = false
        buttonShare.isEnabled = false
        buttonFolder.isEnabled = true
    }

    private fun refreshResults(force: Boolean = false) {
        if (!::resultsContainer.isInitialized) return
        val cfg = PoleStore.getMonitorConfig(this)
        val scope = selectedFolderUri?.toString()
        val current = PoleStore.getRecords(this, scope)
        val pendingPhotos = PoleStore.getPendingPhotos(this, scope).size
        val total = current.mapNotNull { it.distanceM }.sum()
        val signature = "${current.size}|${current.count { it.isMatched }}|$pendingPhotos|$total|${cfg.active}|${cfg.status}"
        if (!force && signature == lastUiSignature) return
        lastUiSignature = signature
        records = current

        resultsContainer.removeAllViews()
        current.forEach { appendRecord(it) }
        textTotal.text = "TOPLAM: ${String.format(Locale("tr", "TR"), "%.2f", total)} m   •   ${current.count { it.isMatched }}/${current.size} eşleşti"
        buttonExcel.isEnabled = current.any { it.isMatched }
        buttonMonitor.isEnabled = !cfg.active
        buttonStop.isEnabled = cfg.active
        buttonScan.isEnabled = !cfg.active
        buttonFolder.isEnabled = !cfg.active
        buttonChangeFolder.isEnabled = true

        if (cfg.active || cfg.status.contains("Pole", ignoreCase = true) || cfg.status.contains("takip", ignoreCase = true)) {
            textStatus.text = cfg.status + if (pendingPhotos > 0) " • $pendingPhotos eşleşmemiş fotoğraf" else ""
        }
    }

    private fun appendRecord(record: PoleRecord) {
        val tr = Locale("tr", "TR")
        val video = record.video
        val hat = video.hat ?: "HAT ?"
        val firma = video.isinSahibi ?: "Firma ?"
        val detail = listOfNotNull(
            video.mahal,
            video.boruTipi,
            video.boruMalzemesi,
            video.capMm?.let { "Ø$it" },
            video.yon
        ).joinToString(" • ")
        val meter = record.distanceM?.let { String.format(tr, "%.2f m", it) } ?: "FOTOĞRAF BEKLENİYOR"
        val gps = if (video.boylam != null && video.enlem != null) {
            String.format(Locale.US, "GPS %.3f / %.3f", video.boylam, video.enlem)
        } else "GPS ?"
        val time = video.timestampMs?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "Saat ?"
        val line = TextView(this).apply {
            text = "$hat   →   $meter\n$firma${if (detail.isNotBlank()) " • $detail" else ""}\n$gps • $time${record.matchDeltaSec?.let { " • foto +${it} sn" } ?: ""}"
            textSize = 15f
            setTextColor(if (record.isMatched) Color.rgb(31, 65, 81) else Color.rgb(177, 105, 40))
            setPadding(12, 12, 12, 12)
        }
        resultsContainer.addView(line)
        resultsContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.rgb(220, 224, 230))
        })
    }

    private fun exportExcel() {
        val matched = records.filter { it.isMatched }
        if (matched.isEmpty()) return
        lifecycleScope.launch {
            textStatus.text = "Pole Excel hazırlanıyor"
            val bytes = withContext(Dispatchers.Default) { PoleXlsxExporter.build(records) }
            pendingXlsx = bytes
            val date = matched.firstNotNullOfOrNull { it.video.tarih }?.replace('/', '-')
                ?: SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
            createExcel.launch("FOTON_POLE_${date}_Metraj.xlsx")
        }
    }

    private fun shareExcel() {
        val uri = lastExcelUri ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Pole Excel'i paylaş"))
    }

    private fun setBusy(busy: Boolean) {
        val active = PoleStore.getMonitorConfig(this).active
        buttonFolder.isEnabled = !busy && !active
        buttonMonitor.isEnabled = !busy && !active
        buttonScan.isEnabled = !busy && !active
        buttonStop.isEnabled = !busy && active
        buttonChangeFolder.isEnabled = !busy
        buttonCrawler.isEnabled = !busy
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

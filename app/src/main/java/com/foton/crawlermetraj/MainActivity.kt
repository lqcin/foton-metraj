package com.foton.crawlermetraj

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
            textFolder.text = name
            textStatus.text = "Klasör hazır"
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
        buttonExcel = findViewById(R.id.buttonExcel)
        buttonShare = findViewById(R.id.buttonShare)
        textFolder = findViewById(R.id.textFolder)
        textStatus = findViewById(R.id.textStatus)
        textTotal = findViewById(R.id.textTotal)
        progress = findViewById(R.id.progress)
        resultsContainer = findViewById(R.id.resultsContainer)

        editTarih.setText(SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).format(Date()))

        buttonFolder.setOnClickListener { folderPicker.launch(selectedFolderUri) }
        buttonAnalyze.setOnClickListener { startAnalysis() }
        buttonExcel.setOnClickListener { exportExcel() }
        buttonShare.setOnClickListener { shareExcel() }
    }

    private fun startAnalysis() {
        val folderUri = selectedFolderUri
        if (folderUri == null) {
            Toast.makeText(this, "Önce video klasörünü seç", Toast.LENGTH_SHORT).show()
            return
        }

        val firma = editFirma.text.toString().trim()
        if (firma.isBlank()) {
            editFirma.error = "Firma adını yaz"
            return
        }

        setBusy(true)
        results = emptyList()
        lastExcelUri = null
        buttonShare.isEnabled = false
        resultsContainer.removeAllViews()
        textTotal.text = "TOPLAM: 0,00 m"
        progress.progress = 0

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@MainActivity, folderUri)
                root?.let { collectVideos(it) } ?: emptyList()
            }

            if (videos.isEmpty()) {
                textStatus.text = "Bu klasörde desteklenen video bulunamadı"
                setBusy(false)
                return@launch
            }

            progress.max = videos.size
            val buffer = mutableListOf<VideoResult>()

            videos.forEachIndexed { index, video ->
                textStatus.text = "${index + 1}/${videos.size} analiz ediliyor: ${video.name ?: "video"}"
                val result = analyzer.analyze(video.uri, video.name ?: "video")
                buffer.add(result)
                progress.progress = index + 1
                appendResult(result)
                updateTotal(buffer)
            }

            results = buffer.toList()
            val ok = results.count { it.isSuccess }
            val bad = results.size - ok
            textStatus.text = if (bad == 0) {
                "Tamamlandı: $ok video"
            } else {
                "Tamamlandı: $ok başarılı, $bad kontrol gerekli"
            }
            buttonExcel.isEnabled = results.any { it.isSuccess }
            setBusy(false)
        }
    }

    private fun collectVideos(folder: DocumentFile): List<DocumentFile> {
        val supported = setOf("mp4", "mov", "mkv", "avi", "m4v", "3gp")
        val out = mutableListOf<DocumentFile>()

        fun walk(node: DocumentFile) {
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it) }
            } else if (node.isFile) {
                val ext = node.name?.substringAfterLast('.', "")?.lowercase(Locale.US)
                if (ext in supported) out.add(node)
            }
        }

        walk(folder)
        return out.sortedBy { it.name?.lowercase(Locale("tr", "TR")) ?: "" }
    }

    private fun appendResult(result: VideoResult) {
        val tr = Locale("tr", "TR")
        val first = result.firstMeter?.let { String.format(tr, "%.2f", it) } ?: "?"
        val last = result.lastMeter?.let { String.format(tr, "%.2f", it) } ?: "?"
        val meter = result.metraj?.let { String.format(tr, "%.2f m", it) } ?: "OKUNAMADI"
        val parsel = result.header.parsel ?: "?"
        val hat = result.header.konum ?: "?"
        val cap = result.header.capMm?.let { "Ø$it" } ?: "Ø?"
        val yon = result.header.yon ?: "?"

        val line = TextView(this).apply {
            text = "$parsel Parsel | $hat | $cap | $yon\nİlk: $first   Son: $last   →   $meter\n${result.fileName}"
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
        buttonAnalyze.isEnabled = !busy
        editFirma.isEnabled = !busy
        editTarih.isEnabled = !busy
        if (busy) {
            buttonExcel.isEnabled = false
            buttonShare.isEnabled = false
        }
    }

    override fun onDestroy() {
        analyzer.close()
        super.onDestroy()
    }
}

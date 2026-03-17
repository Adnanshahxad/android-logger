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
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: ExcludedPackageAdapter

    // Since we don't bind to the service, we track the intent state roughly.
    // In a production app, we'd use a bound service or SharedPrefs for exact state.
    // But since the service runs until explicitly stopped, this static tracker is fine.
    companion object {
        var isServiceRunning = true // Assumes running on open if it was started
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        setupToolbar()
        setupControls()
        setupExclusionList()
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
        binding.switchService.isChecked = isServiceRunning

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                LoggerForegroundService.start(this)
                isServiceRunning = true
            } else {
                LoggerForegroundService.stop(this)
                isServiceRunning = false
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
    }

    private fun setupExclusionList() {
        adapter = ExcludedPackageAdapter(settingsManager.getExcludedPackages().toList()) { pkgToRemove ->
            settingsManager.removeExcludedPackage(pkgToRemove)
            refreshList()
        }

        binding.recyclerViewExcluded.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewExcluded.adapter = adapter

        binding.btnAddExcluded.setOnClickListener {
            val pkg = binding.inputExcludedPackage.text.toString().trim()
            if (pkg.isNotEmpty()) {
                settingsManager.addExcludedPackage(pkg)
                binding.inputExcludedPackage.text?.clear()
                refreshList()
            }
        }
    }

    private fun refreshList() {
        adapter.updateList(settingsManager.getExcludedPackages().toList())
    }
}

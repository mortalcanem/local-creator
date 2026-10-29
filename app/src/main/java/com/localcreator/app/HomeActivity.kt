package com.localcreator.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.Locale

class HomeActivity : ComponentActivity() {
    private lateinit var storage: Storage
    private lateinit var storageText: TextView
    private lateinit var budgetButton: Button
    private var savingBudget = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); storage = Storage(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Local Creator · development 0.9"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Local recording and editing. Background export and long-session work are experimental." })
        storageText = TextView(this).apply { text = "Checking storage…" }; root.addView(storageText)
        budgetButton = Button(this).apply { text = "Change storage budget"; setOnClickListener { if (canManageFiles()) editBudget() } }; root.addView(budgetButton)
        root.addView(Button(this).apply { text = "Long-session recorder (experimental)"; setOnClickListener { if (!savingBudget && !BackgroundExportService.busy) startActivity(Intent(this@HomeActivity, LongRecordingActivity::class.java)) else if (BackgroundExportService.busy) showExportGuard() } })
        root.addView(Button(this).apply { text = "Short-test recorder / import intro / originals"; setOnClickListener { if (canManageFiles()) startActivity(Intent(this@HomeActivity, MainActivity::class.java)) } })
        root.addView(Button(this).apply { text = "Add intro and export / finished videos (tested path)"; setOnClickListener { if (canManageFiles()) startActivity(Intent(this@HomeActivity, IntroExportActivity::class.java)) } })
        root.addView(Button(this).apply { text = "Background export / progress (experimental)"; setOnClickListener { if (!savingBudget && !LongRecordingService.state.value.active) startActivity(Intent(this@HomeActivity, BackgroundExportActivity::class.java)) } })
        root.addView(TextView(this).apply { text = "350 GB default budget is adjustable; 50 GB free-space reserve stays protected. Background exports may be stopped by Android; retry restarts rendering. Two-hour reliability is unverified. Uninstalling deletes private originals." })
        setContentView(root)
    }
    private fun showExportGuard() { AlertDialog.Builder(this).setMessage("Background export is active. Open Background export / progress to monitor or cancel it before recording, editing sources, deleting files or changing budget.").setPositiveButton("OK", null).show() }
    private fun canManageFiles(): Boolean {
        if (savingBudget) return false
        if (BackgroundExportService.busy) { showExportGuard(); return false }
        if (LongRecordingService.state.value.active) { AlertDialog.Builder(this).setMessage("Stop the active long recording before editing, deleting files, changing budget, or opening another camera.").setPositiveButton("OK", null).show(); return false }
        return true
    }
    override fun onResume() { super.onResume(); if (::storage.isInitialized) refreshStorage() }
    private fun refreshStorage() {
        lifecycleScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { storage.snapshot() }
                storageText.text = String.format(Locale.US, "Managed files: %.2f / %.0f GB\nPhone free: %.2f GB · protected reserve: 50 GB", s.used / 1e9, s.budget / 1e9, s.free / 1e9)
            } catch(e: CancellationException) { throw e } catch(e: Exception) { storageText.text = "Storage check failed: ${e.message}" }
        }
    }
    private fun editBudget() {
        if (!canManageFiles()) return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setText((storage.budgetBytes / Storage.GB).toString()); setSelection(text.length) }
        val dialog = AlertDialog.Builder(this).setTitle("Managed storage budget (GB)").setMessage("Usage ceiling only, not preallocated space. The separate 50 GB free-space reserve remains.").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val gb = input.text.toString().trim().toLongOrNull()
                if (gb == null || gb <= 0 || gb > Long.MAX_VALUE / Storage.GB) { input.error = "Enter a valid positive whole number of GB"; return@setOnClickListener }
                if (savingBudget || BackgroundExportService.busy || LongRecordingService.state.value.active) return@setOnClickListener
                savingBudget = true; budgetButton.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false; dialog.setCancelable(false)
                lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { storage.setBudgetGb(gb) }; dialog.dismiss(); refreshStorage() }
                    catch(e: CancellationException) { throw e } catch(e: Exception) { input.error = e.message ?: "Could not save budget"; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true; dialog.setCancelable(true) }
                    finally { savingBudget = false; budgetButton.isEnabled = true }
                }
            }
        }; dialog.show()
    }
}

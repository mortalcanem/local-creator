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
        super.onCreate(state)
        storage = Storage(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Local Creator · development build"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Record locally, then add your intro and export. No paid services." })
        storageText = TextView(this).apply { text = "Checking storage…" }; root.addView(storageText)
        budgetButton = Button(this).apply { text = "Change storage budget"; setOnClickListener { editBudget() } }; root.addView(budgetButton)
        root.addView(Button(this).apply { text = "Record / import intro / manage originals"; setOnClickListener { if (!savingBudget) startActivity(Intent(this@HomeActivity, MainActivity::class.java)) } })
        root.addView(Button(this).apply { text = "Add intro and export / finished videos"; setOnClickListener { if (!savingBudget) startActivity(Intent(this@HomeActivity, IntroExportActivity::class.java)) } })
        root.addView(TextView(this).apply { text = "Budget is a usage ceiling, not reserved space. A 50 GB phone free-space reserve remains protected. Original recordings are private; uninstalling deletes them. Long-session support is still under development." })
        setContentView(root)
    }
    override fun onResume() { super.onResume(); if (::storage.isInitialized) refreshStorage() }
    private fun refreshStorage() {
        lifecycleScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { storage.snapshot() }
                storageText.text = String.format(Locale.US, "Managed files: %.2f / %.0f GB\nPhone free space: %.2f GB\nProtected free-space reserve: 50 GB", s.used / 1e9, s.budget / 1e9, s.free / 1e9)
            } catch(e: CancellationException) { throw e } catch(e: Exception) { storageText.text = "Storage check failed: ${e.message}" }
        }
    }
    private fun editBudget() {
        if (savingBudget) return
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setText((storage.budgetBytes / Storage.GB).toString()); setSelection(text.length) }
        val dialog = AlertDialog.Builder(this).setTitle("Managed storage budget (GB)").setMessage("Enter a whole number, such as 350 or 450. This does not allocate space. Available phone storage and the separate 50 GB reserve still limit actual use. Originals are never automatically deleted.").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val gb = input.text.toString().trim().toLongOrNull()
                if (gb == null || gb <= 0 || gb > Long.MAX_VALUE / Storage.GB) { input.error = "Enter a valid positive whole number of GB"; return@setOnClickListener }
                if (savingBudget) return@setOnClickListener
                savingBudget = true; budgetButton.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { storage.setBudgetGb(gb) }
                        dialog.dismiss(); refreshStorage()
                    } catch(e: CancellationException) { throw e } catch(e: Exception) {
                        input.error = e.message ?: "Could not save budget"
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.setCancelable(true)
                    } finally { savingBudget = false; budgetButton.isEnabled = true }
                }
            }
        }; dialog.show()
    }
}

package com.localcreator.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class HomeActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Local Creator 0.7"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Record locally, then add your intro and export. No paid services." })
        root.addView(Button(this).apply { text = "Record / import intro / manage originals"; setOnClickListener { startActivity(Intent(this@HomeActivity, MainActivity::class.java)) } })
        root.addView(Button(this).apply { text = "Add intro and export / finished videos"; setOnClickListener { startActivity(Intent(this@HomeActivity, IntroExportActivity::class.java)) } })
        root.addView(TextView(this).apply { text = "Development build. The recorder retains its 0.6 heading. AI effects, Shorts and YouTube publishing are not implemented yet. Uninstalling deletes private files." })
        setContentView(root)
    }
}

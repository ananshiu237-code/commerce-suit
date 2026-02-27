package com.example.posmobile

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val resultText = findViewById<TextView>(R.id.resultText)
        resultText.text = "Loading API..."

        // 電腦目前區網 IP
        val apiUrl = "http://192.168.0.227/"

        thread {
            try {
                val req = Request.Builder().url(apiUrl).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: "(empty)"
                runOnUiThread { resultText.text = body }
            } catch (e: Exception) {
                runOnUiThread { resultText.text = "Error: ${e.message}" }
            }
        }
    }
}

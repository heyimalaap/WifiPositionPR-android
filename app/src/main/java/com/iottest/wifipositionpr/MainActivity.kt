package com.iottest.wifipositionpr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class WifiAPEntry(
    val ssid_bssid : String,
    val signal_strength: Int
)

class WifiListManager {
    private var entries = listOf<WifiAPEntry>()
    var number_of_entries : Int = -1
        get() = entries.size

    fun get_ith_entry(i: Int): WifiAPEntry {
        return entries[i]
    }

    fun set_list(new_entries: List<WifiAPEntry>) {
        entries = new_entries
    }
}

data class DataEntry(
    val label: String,
    val ap_list: List<WifiAPEntry>
)

class DataRecordManager {
    var is_recording = false
        private set
    private var current_label = ""
    private var dataset = mutableListOf<DataEntry>()
    var current_ap_list = emptyList<WifiAPEntry>()
        private set

    fun start_recording() {
        is_recording = true
    }

    fun stop_recording() {
        is_recording = false
    }

    fun toggle_recording() {
        if (is_recording)
            stop_recording()
        else
            start_recording()
    }

    fun set_label(label: String) {
        current_label = label
    }

    fun record(label: String, entries: List<WifiAPEntry>) {
        this.current_ap_list = entries
        if (is_recording) {
            dataset.add(
                DataEntry(
                    label,
                    entries
                )
            )
        }
    }

    fun record(entries: List<WifiAPEntry>) {
        record(current_label, entries)
    }

    fun to_json() : String {
        return Gson().toJson(dataset)
    }

    fun current_to_json() : String {
        return Gson().toJson(current_ap_list)
    }

    fun clear() {
        dataset = mutableListOf<DataEntry>()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var wifiManager: WifiManager
    private var dataRecordManager = DataRecordManager()
    private var scanJob: Job? = null
    private var predictJob: Job? = null
    private var wifiListManager : WifiListManager? = null
    private var wifiListAdapter : WifiListAdapter? = null
    private val client = OkHttpClient()
    private var predictTv : TextView? = null

    private val wifiReciever = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) {
                Log.d("wifi-scan", "Failed to complete scan. (intent was null)")
                return
            }

            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                val results = wifiManager.scanResults
                var new_entries = mutableListOf<WifiAPEntry>()
                for (result in results) {
                    new_entries.add(WifiAPEntry(result.SSID + " " + result.BSSID, result.level))
                }
                wifiListManager?.set_list(new_entries.toList())
                dataRecordManager.record(new_entries.toList())
                wifiListAdapter?.notifyDataSetChanged()
            } else {
                Log.d("wifi-scan", "Failed to complete scan. (There was no extra result in intent)")
                return
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        start_scan_job()

        wifiListManager = WifiListManager()
        val wifiRecyclerView = findViewById<RecyclerView>(R.id.wifiRecyclerView)
        wifiListAdapter = WifiListAdapter(wifiListManager!!)
        wifiRecyclerView.layoutManager = LinearLayoutManager(this)
        wifiRecyclerView.adapter = wifiListAdapter

        val scanBtn = findViewById<Button>(R.id.scan_btn)
        val scanStatusIv = findViewById<ImageView>(R.id.status_iv)

        scanBtn.setOnClickListener {
            if (!dataRecordManager.is_recording) {
                // Start recording
                val alertBuilder = AlertDialog.Builder(this)
                alertBuilder.setTitle("Location label")
                alertBuilder.setMessage("Enter the location's label")

                val label_et = EditText(this)
                alertBuilder.setView(label_et)

                alertBuilder.setPositiveButton("Ok", object : DialogInterface.OnClickListener {
                    override fun onClick(dialog: DialogInterface?, whichBtn: Int) {
                        dataRecordManager.set_label(label_et.text.toString())
                        dataRecordManager.toggle_recording()
                        scanBtn.text = "Stop Scan"
                        scanStatusIv.setImageResource(R.drawable.scan_running)
                    }
                })

                alertBuilder.setNegativeButton("Cancel", object : DialogInterface.OnClickListener {
                    override fun onClick(dialog: DialogInterface?, whichBtn: Int) {
                        // Stub
                    }
                })

                alertBuilder.show()
            } else {
                // Stop recording
                dataRecordManager.toggle_recording()
                scanBtn.text = "Start Scan"
                scanStatusIv.setImageResource(R.drawable.scan_not_running)
            }
        }

        val finishBtn = findViewById<Button>(R.id.finish_btn)
        finishBtn.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = Request.Builder()
                        .url("http://192.168.29.191:8000/feed")
                        .post(
                            dataRecordManager.to_json()
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                        )
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")

                        Log.d("http", response.body!!.string())
                    }
                } catch (exception: IOException) {
                    Log.d("http", "Failed to send post request")
                }
            }
        }

        predictTv = findViewById<TextView>(R.id.predict_tv)
    }

    override fun onPause() {
        super.onPause()
        stop_scan_job()
        stop_predict_job()
    }

    override fun onResume() {
        super.onResume()
        start_scan_job()
        start_predict_job()
    }

    override fun onRestart() {
        super.onRestart()
        stop_scan_job()
        stop_predict_job()
        start_scan_job()
        start_predict_job()
    }

    fun start_scan_job() {
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiReciever, intentFilter)
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                wifiManager.startScan()
                delay(1000)
            }
        }
    }

    fun stop_scan_job() {
        try {
            scanJob?.cancel()
            unregisterReceiver(wifiReciever)
            scanJob = null
        } catch (e: IllegalArgumentException) {
            Log.d("wifi-scan", "wifiReciever was not attached.")
        }
    }

    fun start_predict_job() {
        predictJob?.cancel()
        predictJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val request = Request.Builder()
                        .url("http://192.168.29.191:8000/predict")
                        .post(
                            dataRecordManager.current_to_json()
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                        )
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                        predictTv?.text = response.body!!.string()
                    }
                } catch (exception: IOException) {
                    Log.d("http", "Failed to send post request")
                }
                delay(1000)
            }
        }
    }

    fun stop_predict_job() {
        predictJob?.cancel()
        predictJob = null
    }
}
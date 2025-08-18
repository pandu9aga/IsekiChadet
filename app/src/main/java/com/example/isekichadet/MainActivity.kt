package com.example.isekichadet

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

// Data class untuk Record
data class Record(
    val No_Produksi: String,
    val No_Chasis_Kanban: String,
    val No_Chasis_Scan: String,
    val Time: String,
    val Status_Record: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var etDate: EditText
    private lateinit var rvRecords: RecyclerView
    private lateinit var recordAdapter: RecordAdapter
    private val records = ArrayList<Record>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDate = findViewById(R.id.etDate)
        rvRecords = findViewById(R.id.rvRecords)

        // Setup RecyclerView
        rvRecords.layoutManager = LinearLayoutManager(this)
        recordAdapter = RecordAdapter(records)
        rvRecords.adapter = recordAdapter

        // Set default tanggal hari ini dan fetch data
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        etDate.setText(today)
        loadRecords(today)

        // Listener untuk EditText tanggal
        etDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val dateParts = etDate.text.toString().split("-")
            if (dateParts.size == 3) {
                calendar.set(dateParts[0].toInt(), dateParts[1].toInt() - 1, dateParts[2].toInt())
            }

            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePicker = DatePickerDialog(
                this,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val dateString = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
                    etDate.setText(dateString)
                    loadRecords(dateString) // fetch data langsung
                },
                year,
                month,
                day
            )
            datePicker.show()
        }

        // Bottom Navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.selectedItemId = R.id.nav_dashboard // tandai aktif
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // Sudah di Dashboard
                    true
                }
                R.id.nav_record -> {
                    val intent = Intent(this, RecordActivity::class.java)
                    // Hancurkan semua activity sebelumnya di back stack
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    //finish() // hancurkan MainActivity
                    true
                }
                else -> false
            }
        }
    }

    private fun loadRecords(date: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://192.168.173.207/iseki_chadet/public/api/records?Day_Record=$date") // Ganti URL API sesuai server
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gagal terhubung ke server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val jsonData = response.body?.string()

                if (!response.isSuccessful || jsonData.isNullOrEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Gagal ambil data: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                try {
                    val jsonObject = JSONObject(jsonData)
                    val jsonArray: JSONArray = jsonObject.getJSONArray("data")

                    records.clear()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        records.add(
                            Record(
                                obj.getString("No_Produksi"),
                                obj.getString("No_Chasis_Kanban"),
                                obj.getString("No_Chasis_Scan"),
                                obj.getString("Time"),
                                obj.getString("Status_Record")
                            )
                        )
                    }

                    runOnUiThread {
                        recordAdapter.notifyDataSetChanged()
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Parsing error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

        })
    }
}

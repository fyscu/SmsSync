package com.feiyang.smssync

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.Telephony
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.*
import android.view.WindowManager
import android.os.Build
import android.content.Intent

fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)  // 确保 timestamp 是 Long 类型的毫秒时间戳
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(date)
}

class MainActivity : AppCompatActivity() {

    private val smsList = mutableListOf<Sms>()
    private lateinit var adapter: SmsAdapter
    private lateinit var observer: SmsObserver
    private lateinit var db: AppDatabase

    // 权限请求 launcher
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            Log.d("权限返回", "结果: $result")
            if (result.values.all { it }) {
                startObserve()
                loadSavedSms()
            } else {
                Toast.makeText(this, "必须允许读取短信才能正常工作", Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, ForegroundService::class.java))
        } else {
            startService(Intent(this, ForegroundService::class.java))
        }
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db = AppDatabase.getDatabase(this)

        adapter = SmsAdapter(smsList)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.exportButton).setOnClickListener {
            exportSmsToFile()
        }

        checkPermissionsAndStart()
    }

    // ✅ 权限检查逻辑
    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (denied.isEmpty()) {
            startObserve()
            loadSavedSms()
        } else {
            permissionLauncher.launch(permissions)
        }
    }
    fun Sms.toLocalSms(): LocalSms {

        return LocalSms(
            address = this.address,
            body = this.body,
            timestamp = this.date
        )
    }

    private fun loadSavedSms() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                db.smsDao().getAll()
            }
            val converted = saved.map {
                Sms(
                    id = 0.toString(),
                    address = it.address,
                    body = it.body,
                    date = it.timestamp
                )
            }
            smsList.addAll(converted)
            adapter.submit(smsList.toList())
        }
    }



    private fun startObserve() {
        observer = SmsObserver(this) { sms ->
            smsList.add(0, sms)
            adapter.submit(smsList.toList())
            lifecycleScope.launch(Dispatchers.IO) {
                db.smsDao().insert(sms.toLocalSms())
            }
            uploadSms(sms)
            ForegroundService.updateNotification(this, smsList.size)
        }
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )
    }


    private fun uploadSms(sms: Sms) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = JSONObject()
                        .put("address", sms.address)
                        .put("body", sms.body)
                        .put("timestamp", sms.date)

                    val body = json.toString()
                        .toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url("https://focapi.feiyang.ac.cn/v1/user/smscatcher?uploadtoken=xxx")
                        .post(body)
                        .build()

                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    val response = client.newCall(request).execute()
                    response.close()
                }
            } catch (e: Exception) {
                Log.e("上传失败", e.toString())
            }
        }
    }

    private fun exportSmsToFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "sms_export")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "sms_export.csv")
                val writer = FileWriter(file)
                writer.write("Address,Body,Timestamp\n")
                for (sms in smsList) {
                    writer.write("\"${sms.address}\",\"${sms.body}\",\"${sms.date}\"\n")
                }
                writer.flush()
                writer.close()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已导出到: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::observer.isInitialized) {
            contentResolver.unregisterContentObserver(observer)
        }
    }
}

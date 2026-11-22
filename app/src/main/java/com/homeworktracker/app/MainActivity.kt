package com.homeworktracker.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), ScanResultBus.Listener {

    private lateinit var webView: WebView
    private var pendingClassName: String = ""
    private var pendingStudents: ArrayList<String> = arrayListOf()
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        proceedToScreenCapturePermission()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_CLASS_NAME, pendingClassName)
                putStringArrayListExtra(ScreenCaptureService.EXTRA_STUDENTS, pendingStudents)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(
                this,
                "بدأت مشاركة الشاشة — افتح واتساب الآن ومرر رسائل الواجب",
                Toast.LENGTH_LONG
            ).show()
        } else {
            runJs("window.HomeworkScannerBridge && window.HomeworkScannerBridge.onScanCancelled('لم يتم منح إذن مشاركة الشاشة');")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        // ربط جسر الأندرويد بنفس الاسم المتوقع داخل صفحة النظام
        webView.addJavascriptInterface(WhatsAppScannerJsBridge(), "AndroidWhatsAppScanner")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://wa.me/") || url.startsWith("whatsapp://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "تطبيق واتساب غير مثبت", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: return false
                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        ScanResultBus.register(this)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        ScanResultBus.unregister(this)
        super.onDestroy()
    }

    private fun startScanFlow(payloadJson: String) {
        try {
            val obj = JSONObject(payloadJson)
            pendingClassName = obj.optString("className", "")
            val arr = obj.optJSONArray("students") ?: JSONArray()
            pendingStudents = ArrayList()
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) pendingStudents.add(name)
            }
        } catch (_: Exception) {
            pendingClassName = ""
            pendingStudents = arrayListOf()
        }

        // 1. طلب إذن الظهور فوق التطبيقات الأخرى (اختياري لعرض شريط التحكم فوق واتساب)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("تفعيل شريط التحكم العائم")
                .setMessage("لكي يظهر زر «إنهاء المسح» وعداد الطلاب فوق واتساب أثناء مشاركة الشاشة، يمكنك تفعيل إذن «الظهور فوق التطبيقات الأخرى».")
                .setPositiveButton("تفعيل الإذن") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    runJs("window.HomeworkScannerBridge && window.HomeworkScannerBridge.onScanCancelled('بعد تفعيل الإذن اضغط مشاركة الشاشة مرة أخرى');")
                }
                .setNegativeButton("متابعة بدون شريط عائم") { _, _ ->
                    checkNotificationsAndRequestProjection()
                }
                .setCancelable(false)
                .show()
            return
        }

        checkNotificationsAndRequestProjection()
    }

    private fun checkNotificationsAndRequestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceedToScreenCapturePermission()
        }
    }

    private fun proceedToScreenCapturePermission() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopScanFlow() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun runJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    // ====================================================
    // استقبال نتائج المسح المباشر من ScreenCaptureService
    // ====================================================
    override fun onNameDetected(name: String) {
        val quoted = JSONObject.quote(name)
        runJs("window.HomeworkScannerBridge && window.HomeworkScannerBridge.receiveSingleName($quoted);")
    }

    override fun onStatusChanged(statusText: String) {
        val quoted = JSONObject.quote(statusText)
        runJs("window.HomeworkScannerBridge && window.HomeworkScannerBridge.updateAndroidStatus($quoted);")
    }

    override fun onScanFinished(allDetectedNames: List<String>) {
        val jsonArr = JSONArray()
        allDetectedNames.forEach { jsonArr.put(it) }
        runJs("window.HomeworkScannerBridge && window.HomeworkScannerBridge.finishAndroidScan($jsonArr);")
    }

    // ====================================================
    // الجسر البرمجي الذي يستدعيه زر "مشاركة الشاشة" في HTML
    // ====================================================
    inner class WhatsAppScannerJsBridge {
        @JavascriptInterface
        fun startScan(payloadJson: String) {
            runOnUiThread {
                startScanFlow(payloadJson)
            }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread {
                stopScanFlow()
            }
        }
    }
}

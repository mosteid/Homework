package com.homeworktracker.app

import java.util.concurrent.CopyOnWriteArraySet

/**
 * قناة اتصال آمنة بين خدمة مشاركة الشاشة (ScreenCaptureService) والواجهة الرئيسية (MainActivity)
 */
object ScanResultBus {

    interface Listener {
        fun onNameDetected(name: String)
        fun onStatusChanged(statusText: String)
        fun onScanFinished(allDetectedNames: List<String>)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val detectedNames = LinkedHashSet<String>()

    @Synchronized
    fun resetSession() {
        detectedNames.clear()
    }

    @Synchronized
    fun addDetectedName(name: String): Boolean {
        val cleaned = name.trim()
        if (cleaned.length < 2) return false
        val isNew = detectedNames.add(cleaned)
        if (isNew) {
            listeners.forEach { it.onNameDetected(cleaned) }
        }
        return isNew
    }

    @Synchronized
    fun getDetectedCount(): Int = detectedNames.size

    @Synchronized
    fun getDetectedNamesSnapshot(): List<String> = detectedNames.toList()

    fun notifyStatus(statusText: String) {
        listeners.forEach { it.onStatusChanged(statusText) }
    }

    fun notifyFinished() {
        val snapshot = getDetectedNamesSnapshot()
        listeners.forEach { it.onScanFinished(snapshot) }
    }

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }
}

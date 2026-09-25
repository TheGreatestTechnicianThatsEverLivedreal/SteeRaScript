package com.example.defensivesensor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_NOTIF = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val startBtn = Button(this).apply {
            text = "Start Max-Rate Sampling"
            setOnClickListener { checkAndStartService() }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Sampling"
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, DefensiveSensorService::class.java)
                stopService(serviceIntent)
            }
        }

        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIF)
                return
            }
        }
        startSensorService()
    }

    private fun startSensorService() {
        val serviceIntent = Intent(this, DefensiveSensorService::class.java).apply {
            putExtra(DefensiveSensorService.EXTRA_SENSOR_TYPE, Sensor.TYPE_ACCELEROMETER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIF) {
            startSensorService()
        }
    }
}

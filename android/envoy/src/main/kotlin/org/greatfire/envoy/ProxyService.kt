package org.greatfire.envoy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class ProxyService : Service() {
    // binder given to clients
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        // return this instance of ProxyService so clients can call public methods
        fun getService(): ProxyService = this@ProxyService
    }

    @SuppressLint("NewApi")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // this service is intended to use a proxy to link a local, unauthenticated url
        // to a remote proxy url that requires authentication or certs as parameters
        val localUrl: String = (intent.getStringExtra(LOCAL_URL) ?: "socks5://127.0.0.1")  // check for port conflicts
        val localPort: Int = (intent.getIntExtra(LOCAL_PORT, 1081))
        val proxyUrl: String = (intent.getStringExtra(PROXY_URL) ?: "obfs4://foo")

        // should exit if parameters aren't available?

        // TODO: how to handle scenario where prosy is already running?

        Log.d(TAG, "intent received, start proxy service for " + localUrl + ":" + localPort + " -> " + proxyUrl)

        // increment port if needed
        if (localPort > currentPort) {
            Log.d(TAG, "port " + localPort + " is available")
            //Log.d(TAG, "current port: " + currentPort + " / starting service on port: " + localPort)
            //currentPort = localPort
        } else {
            Log.d(TAG, "port " + localPort + " may be in use")
            //Log.d(TAG, "service may be running on port: " + currentPort + " / starting service on port: " + currentPort + 1)
            //currentPort = currentPort + 1
        }

        // TODO: not sure of how to adjust port, not clear how to feed back updated port to main app
        //   maybe just don't start a new proxy if one is currently running on the port?
        currentPort = localPort

        // stop current process if needed
        currentProcess?.let {
            Log.d(TAG, "gost may already be running")
            //Log.d(TAG, "attempting to kill current process...")
            //it.destroy()
            //currentProcess = null
        }

        // build a config file to avoid issues with strings that might require quotes as command line arguments
        val config = JSONObject()
        val sNodes = JSONArray()
        Log.d(TAG, "adding local url: " + localUrl + ":" + localPort)
        sNodes.put(localUrl + ":" + localPort)
        val cNodes = JSONArray()
        cNodes.put(proxyUrl)
        config.put("Debug", true)
        config.put("Retries", 0)
        config.put("ServeNodes", sNodes)
        config.put("ChainNodes", cNodes)

        // write config file
        val configFile = File(ContextCompat.getNoBackupFilesDir(this), "gost.json")
        configFile.writeText(config.toString())

        // copied from ShadowsocksService, may not be needed
        val channelId = "proxy-channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "proxy-channel"
            val channel = NotificationChannel(
                channelId, name, NotificationManager.IMPORTANCE_LOW)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // copied from ShadowsocksService, may not be needed
        @Suppress("DEPRECATION")
        val notification: Notification = Notification.Builder(this, channelId)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentTitle("Proxy is running")
            .setContentText("Proxy is running")
            // deprecated in API level 26, see NotificationChannel#setImportance(int)
            .setPriority(Notification.PRIORITY_LOW)
            .setTicker("Proxy is running")
            .build()
        startForeground(SystemClock.uptimeMillis().toInt(), notification)

        // this is based on the SS_LOCAL_STARTED intent broadcast by ShadowsocksService
        // however, it is unclear what receives the broadcast. nothing in envoy references
        // SS_LOCAL_STARTED and apps watch for the response from NetworkIntentService
        val broadcastIntent = Intent()

        // TODO - currently the gost binary must be manually copied into /lib/arm64-v8a/
        //   it will be extracted during installation to a directory where it can be executed
        val nativeLibraryDir = applicationInfo.nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "gost-linux-armv8.so")
        val executablePath = executableFile.absolutePath
        if (executableFile.exists()) {
            Runnable {
                try {

                    Log.d(TAG, "EXECUTE: " + executablePath + " -C " + configFile.absolutePath)

                    // TODO - gost will bind a port and continue running after the app is closed.
                    //   code must be added to manage the process and terminate it with the app.
                    val cmdArgs = arrayOf(executablePath, "-C", configFile.absolutePath)
                    currentProcess = Runtime.getRuntime().exec(cmdArgs)

                    // need to check for error?
                    // need kill method?

                    //val broadcastIntent = Intent()
                    Log.d(TAG, "started proxy at " + localUrl + ":" + currentPort)
                    broadcastIntent.action = PROXY_STARTED
                    broadcastIntent.putExtra(ACTUAL_URL, localUrl)
                    broadcastIntent.putExtra(ACTUAL_PORT, currentPort)
                    sendBroadcast(broadcastIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION WHEN RUNNING EXECUTABLE", e)
                    broadcastIntent.action = PROXY_ERROR_RUN
                    sendBroadcast(broadcastIntent)
                }
            }.run()
        } else {
            Log.e(TAG, "EXECUTABLE " + executablePath + " DOES NOT EXIST")
            broadcastIntent.action = PROXY_ERROR_EXE
            sendBroadcast(broadcastIntent)
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    companion object {
        const val TAG = "FOO" // "ProxyService"
        const val LOCAL_URL = "LOCAL_URL"
        const val LOCAL_PORT = "LOCAL_PORT"
        const val PROXY_URL = "PROXY_URL"
        const val PROXY_STARTED = "PROXY_STARTED"
        const val PROXY_ERROR_RUN = "PROXY_ERROR_RUN"
        const val PROXY_ERROR_EXE = "PROXY_ERROR_EXE"
        const val ACTUAL_URL = "ACTUAL_URL"
        const val ACTUAL_PORT = "ACTUAL_PORT"

        private var currentPort: Int = 0
        private var currentProcess: Process? = null

        fun getPort(): Int {
            return currentPort
        }

        fun getProcess(): Process? {
            return currentProcess
        }

        fun killProcess() {
            currentProcess?.let {
                Log.d(TAG, "attempting to kill current process running on port " + currentPort +"...")
                it.destroy()
                currentPort = 0
                currentProcess = null
            }
        }
    }
}
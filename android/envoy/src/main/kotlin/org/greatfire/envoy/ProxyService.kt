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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject
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

        // START_REDELIVER_INTENT: if this service's process is killed while it is started then it
        // will be scheduled for a restart and the last delivered Intent re-delivered to it again
        
        val broadcastIntent = Intent(PROXY_SERVICE_BROADCAST)

        // this service is intended to use a proxy to link a local, unauthenticated url
        // to a remote proxy url that requires authentication or certs as parameters
        val localUrl: String = (intent.getStringExtra(LOCAL_URL) ?: LOCAL_URL_MISSING)  // check for port conflicts
        val localPort: Int = (intent.getIntExtra(LOCAL_PORT, LOCAL_PORT_MISSING))
        val proxyUrl: String = (intent.getStringExtra(PROXY_URL) ?: PROXY_URL_MISSING)

        // ignore localUrl?  probably should always be 127.0.0.1

        // should exit if parameters aren't available?
        if (localUrl == LOCAL_URL_MISSING || localPort == LOCAL_PORT_MISSING || proxyUrl == PROXY_URL_MISSING) {
            Log.d(TAG, "intent received, but some parameters are missing: " + localUrl + " / " + localPort + " / " + proxyUrl)

            Log.e(TAG, "PARAMETERS MISSING, CANNOT START PROY SERVICE")
            broadcastIntent.putExtra(PROXY_SERVICE_RESULT, PROXY_ERROR_PARAMETERS)
            LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(broadcastIntent)
            return START_REDELIVER_INTENT
        } else {
            Log.d(TAG, "intent received, start proxy service with parameters: " + localUrl + " / " + localPort + " / " + proxyUrl)
        }

        // TODO: how to handle scenario where proxy is already running?

        // TODO: not sure of how to adjust port, not clear how to feed back updated port to main app
        //   maybe just don't start a new proxy if one is currently running on the port?

        // check if process is running
        currentProcess?.let {
            Log.d(TAG, "gost may already be running")
            if (localPort == currentPort && proxyUrl == currentUrl) {
                Log.d(TAG, "process already running on port " + localPort + " for proxy " + proxyUrl)
                // exit here?
                broadcastIntent.putExtra(PROXY_SERVICE_RESULT, PROXY_RUNNING)
                LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(broadcastIntent)
                return START_REDELIVER_INTENT
            } else {
                Log.d(TAG, "process currently running on port " + currentPort + " for proxy " + currentUrl)
                killProcess()
            }
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

                    Log.d(TAG, "started proxy at " + localUrl + ":" + currentPort)
                    broadcastIntent.putExtra(PROXY_SERVICE_RESULT, PROXY_STARTED)
                    LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(broadcastIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION WHEN RUNNING EXECUTABLE", e)
                    broadcastIntent.putExtra(PROXY_SERVICE_RESULT, PROXY_ERROR_RUN)
                    LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(broadcastIntent)
                }
            }.run()
        } else {
            Log.e(TAG, "EXECUTABLE " + executablePath + " DOES NOT EXIST")
            broadcastIntent.putExtra(PROXY_SERVICE_RESULT, PROXY_ERROR_EXE)
            LocalBroadcastManager.getInstance(this@ProxyService).sendBroadcast(broadcastIntent)
        }

        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Log.e(TAG, "STOPPING PROXY SERVICE...")

        // kill process when application closes
        killProcess()

        // destroy the service
        stopSelf()
    }

    companion object {
        const val TAG = "ProxyService"
        const val LOCAL_URL = "LOCAL_URL"
        const val LOCAL_PORT = "LOCAL_PORT"
        const val PROXY_URL = "PROXY_URL"
        const val LOCAL_URL_MISSING = "LOCAL_URL_MISSING"
        const val LOCAL_PORT_MISSING = -1
        const val PROXY_URL_MISSING = "PROXY_URL_MISSING"
        const val PROXY_SERVICE_BROADCAST = "PROXY_SERVICE_BROADCAST"
        const val PROXY_SERVICE_RESULT = "PROXY_SERVICE_RESULT"
        const val PROXY_STARTED = 100
        const val PROXY_RUNNING = -101
        const val PROXY_ERROR_PARAMETERS = -102
        const val PROXY_ERROR_RUN = -103
        const val PROXY_ERROR_EXE = -104
        const val ACTUAL_URL = "ACTUAL_URL"
        const val ACTUAL_PORT = "ACTUAL_PORT"

        private var currentPort: Int = 0
        private var currentUrl: String? = null
        private var currentProcess: Process? = null

        fun getPort(): Int {
            return currentPort
        }

        fun getUrl(): String? {
            return currentUrl
        }

        fun getProcess(): Process? {
            return currentProcess
        }

        fun killProcess() {
            currentProcess?.let {
                Log.d(TAG, "attempting to kill current process running on port " + currentPort)
                it.destroy()
                currentPort = 0
                currentProcess = null
                return
            }
            Log.d(TAG, "no current process to kill")
        }
    }
}
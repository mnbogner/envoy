package org.greatfire.envoy

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONArray
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


// IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
private const val ACTION_SUBMIT = "org.greatfire.envoy.action.SUBMIT"
private const val ACTION_QUERY = "org.greatfire.envoy.action.QUERY"

private const val EXTRA_PARAM_SUBMIT = "org.greatfire.envoy.extra.PARAM_SUBMIT"

// Defines a custom Intent action
const val BROADCAST_VALID_URL_FOUND = "org.greatfire.envoy.VALID_URL_FOUND"
const val BROADCAST_NO_URL_FOUND = "org.greatfire.envoy.NO_URL_FOUND"

// Defines the key for the status "extra" in an Intent
const val EXTENDED_DATA_VALID_URLS = "org.greatfire.envoy.VALID_URLS"
const val EXTENDED_DATA_INVALID_URLS = "org.greatfire.envoy.INVALID_URLS"

const val PREF_VALID_URLS = "validUrls"

/**
 * An [IntentService] subclass for handling asynchronous task requests in
 * a service on a separate handler thread.

 */
private const val DEFAULT_USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/52.0.2743.82 Safari/537.36")

class NetworkIntentService : IntentService("NetworkIntentService") {
    // https://android.googlesource.com/platform/frameworks/base.git/+/oreo-release/services/core/java/com/android/server/connectivity/NetworkMonitor.java
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NetworkIntentService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NetworkIntentService destroyed")
    }

    private var validUrls = Collections.synchronizedList(mutableListOf<String>())

    // Binder given to clients
    private val binder = NetworkBinder()

    inner class NetworkBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): NetworkIntentService = this@NetworkIntentService
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT -> {
                Log.d(TAG, "SUBMIT ACTION")
                val urls = intent.getStringArrayListExtra(EXTRA_PARAM_SUBMIT)
                handleActionSubmit(urls)
            }
            ACTION_QUERY -> {
                Log.d(TAG, "QUERY ACTION")
                handleActionQuery()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }


    // sorted by latency, from the the fastest one
    fun getValidUrls(): List<String> {

        Log.d(TAG, "getValidUrls CALLED")

        if (validUrls.isNotEmpty()) {
            return validUrls
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val savedValidUrlsStr = sharedPreferences.getString(PREF_VALID_URLS, "[]")
        val savedValidUrls = JSONArray(savedValidUrlsStr)
        // then get it from saved preferences

        val validUrls = ArrayList<String>()
        for (i in 0 until savedValidUrls.length()) {
            validUrls.add(savedValidUrls.getString(i))
        }
        return validUrls
    }

    /**
     * Handle action Submit in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionSubmit(urls: List<String>?,
                                   captive_portal_url: String = "https://www.google.com/generate_204") {

        Log.d(TAG, "HANDLE SUBMIT")

        val executor: Executor = Executors.newSingleThreadExecutor()
        urls?.forEachIndexed { index, envoyUrl ->

            Log.d(TAG, "CHECKING URL " + index + ": " + envoyUrl)

            val myBuilder = CronetEngine.Builder(applicationContext)
            val cronetEngine: CronetEngine = myBuilder
                    .setEnvoyUrl(envoyUrl)
                    .setUserAgent(DEFAULT_USER_AGENT).build()

            val requestBuilder = cronetEngine.newUrlRequestBuilder(
                    captive_portal_url,
                    MyUrlRequestCallback(envoyUrl),
                    executor
            )

            val request: UrlRequest = requestBuilder.build()
            request.start()
            Log.d(TAG, "enqueue $index-th url $envoyUrl to test $captive_portal_url")
        }
    }

    /**
     * Handle action Query in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionQuery() {

        Log.d(TAG, "HANDLE QUERY")

        val localIntent = Intent(BROADCAST_VALID_URL_FOUND).apply {
            // Puts the status into the Intent
            putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
        }
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent)
    }

    companion object {
        private const val TAG = "NetworkIntentService"

        /**
         * Starts this service to perform action Submit with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun submit(context: Context, urls: List<String>) {
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_SUBMIT
                putStringArrayListExtra(EXTRA_PARAM_SUBMIT, ArrayList<String>(urls))
            }
            context.startService(intent)
        }

        /**
         * Starts this service to perform action Query with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun enqueueQuery(context: Context) {
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_QUERY
            }
            context.startService(intent)
        }
    }

    inner class MyUrlRequestCallback(private val envoyUrl: String) : UrlRequest.Callback() {

        override fun onRedirectReceived(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                newLocationUrl: String?
        ) {

            Log.d(TAG, "CALLBACK REDIRECT")

            Log.i(TAG, "onRedirectReceived method called.")
            // You should call the request.followRedirect() method to continue
            // processing the request.
            request?.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest?, info: UrlResponseInfo?) {

            Log.d(TAG, "CALLBACK RESPONSE")

            Log.i(TAG, "onResponseStarted method called.")
            // You should call the request.read() method before the request can be
            // further processed. The following instruction provides a ByteBuffer object
            // with a capacity of 102400 bytes to the read() method.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onReadCompleted(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                byteBuffer: ByteBuffer?
        ) {

            Log.d(TAG, "CALLBACK COMPLETED")

            Log.i(TAG, "onReadCompleted method called.")
            // You should keep reading the request until there's no more data.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {

            Log.d(TAG, "CALLBACK SUCCEEDED")

            if (info != null) {

                Log.d(TAG, "ADD URL: " + envoyUrl)

                this@NetworkIntentService.validUrls.add(envoyUrl)
                // this@NetworkIntentService.handleActionQuery()
                val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
                val editor: SharedPreferences.Editor = sharedPreferences.edit()
                val json = JSONArray(this@NetworkIntentService.validUrls)
                editor.putString(PREF_VALID_URLS, json.toString())
                editor.apply()

                val localIntent = Intent(BROADCAST_VALID_URL_FOUND).apply {
                    // Puts the status into the Intent
                    putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
                }
                LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
            } else {
                Log.d(TAG, "INFO NULL")
            }
        }

        override fun onFailed(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                error: CronetException?
        ) {
            Log.d(TAG, "CALLBACK FAILED: " + error)

            val localIntent = Intent(BROADCAST_VALID_URL_FOUND).apply {
                // Puts the status into the Intent
                putExtra(EXTENDED_DATA_INVALID_URLS, "foo")
            }
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            Log.i(TAG, "onFailed method called for " + info?.url + " " + error)
        }
    }
}




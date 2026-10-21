package com.nahuel.spenpresenter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Encuentra el agente de la PC por mDNS, para no tener que tipear la IP.
 *
 * El agente se publica como `_spenpresenter._tcp`; aca lo buscamos con
 * NsdManager, que es el cliente mDNS que ya trae Android.
 */
class Discovery(context: Context) {

    data class Agent(val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Busca el agente hasta [timeoutMillis]. Devuelve null si no aparece
     * ninguno, que es lo normal si la PC esta apagada o el router filtra
     * multicast (pasa en varias redes de hoteles y universidades).
     */
    suspend fun find(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Agent? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val done = AtomicBoolean(false)

                fun finish(agent: Agent?) {
                    // NsdManager puede llamarnos mas de una vez (varias
                    // interfaces de red, por ejemplo); solo la primera cuenta.
                    if (done.compareAndSet(false, true)) continuation.resume(agent)
                }

                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        Log.i(TAG, "encontrado: ${info.serviceName}")
                        resolve(info, ::finish)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "no se pudo iniciar la busqueda (error $errorCode)")
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }

                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

                continuation.invokeOnCancellation {
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
            }
        }

    private fun resolve(info: NsdServiceInfo, onResolved: (Agent?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(info, onResolved)
        } else {
            resolveLegacy(info, onResolved)
        }
    }

    /**
     * Android 14+: resolveService quedo deprecado en favor de esta API.
     *
     * La anotacion no es decorativa: sin ella lint no puede relacionar este
     * cuerpo con el chequeo de version que hay en [resolve], y marca cinco
     * errores de NewApi.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveModern(info: NsdServiceInfo, onResolved: (Agent?) -> Unit) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                val host = info.hostAddresses
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
                if (host != null) {
                    onResolved(Agent(host, info.port))
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "no se pudo resolver el servicio (error $errorCode)")
                onResolved(null)
            }
        }
        nsd.registerServiceInfoCallback(info, Executors.newSingleThreadExecutor(), callback)
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo, onResolved: (Agent?) -> Unit) {
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    onResolved(host?.let { Agent(it, resolved.port) })
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "no se pudo resolver el servicio (error $errorCode)")
                    onResolved(null)
                }
            },
        )
    }

    companion object {
        /** Tiene que coincidir con el SERVICE_TYPE de pc-agent/agent.py. */
        const val SERVICE_TYPE = "_spenpresenter._tcp"
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        private const val TAG = "Discovery"
    }
}

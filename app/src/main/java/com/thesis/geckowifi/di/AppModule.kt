package com.thesis.geckowifi.di

import android.content.Context
import com.thesis.geckowifi.data.local.HistoryStore
import com.thesis.geckowifi.data.local.InMemoryHistoryStore
import com.thesis.geckowifi.data.local.InMemoryTrustPreferenceStore
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.enterprise.EnterpriseConfigurator
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import com.thesis.geckowifi.verification.VerificationEngine

object AppModule {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val certProbe: CertProbe by lazy { CertProbe() }
    val decisionCache: DecisionCache by lazy { DecisionCache() }
    val historyStore: HistoryStore by lazy { InMemoryHistoryStore() }
    val trustPreferences: TrustPreferenceStore by lazy { InMemoryTrustPreferenceStore() }
    val enterpriseConfigurator: EnterpriseConfigurator by lazy { EnterpriseConfigurator() }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val networkObserver: NetworkObserver by lazy { NetworkObserver(appContext) }

    val geoQueryEncoder: GeoQueryEncoder by lazy { GeoQueryEncoder() }
    val geckoClient: GeckoClient by lazy { GeckoClient() }
    val verificationEngine: VerificationEngine by lazy {
        VerificationEngine(geckoClient, certProbe, decisionCache, geoQueryEncoder)
    }
}
package com.example.silentflow

import android.app.Application
import android.content.Context
import com.acesso.acessobio_android.onboarding.UnicoSDK
import com.acesso.acessobio_android.onboarding.models.Environment
import com.acesso.acessobio_android.onboarding.models.UnicoSDKConfig

class SilentFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The first session opens on app launch, so it is ready by the time
        // the user reaches the flow.
        initializeUnicoSdk(this)
    }

    companion object {
        // Fire-and-forget: opens a new collection session. A failure (e.g. no
        // network) is not reported back; the next call opens a new session.
        fun initializeUnicoSdk(context: Context) {
            UnicoSDK.initializeSDK(
                context.applicationContext,
                UnicoSDKConfig(PocConfig.SDK_KEY, context.packageName, Environment.UAT),
            )
        }
    }
}

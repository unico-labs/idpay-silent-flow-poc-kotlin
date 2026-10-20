package com.example.silentflow

import android.app.Application
import android.content.Context
import com.acesso.acessobio_android.onboarding.UnicoSDK
import com.acesso.acessobio_android.onboarding.models.Environment
import com.acesso.acessobio_android.onboarding.models.UnicoSDKConfig

class SilentFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // On app launch, so the collection is ready by the time the user
        // reaches the flow.
        initializeUnicoSdk(this)
    }

    companion object {
        // Fire-and-forget: a failure (e.g. no network on launch) is not
        // reported back, so callers re-run it when the collection fails.
        fun initializeUnicoSdk(context: Context) {
            UnicoSDK.initializeSDK(
                context.applicationContext,
                UnicoSDKConfig(PocConfig.SDK_KEY, context.packageName, Environment.UAT),
            )
        }
    }
}

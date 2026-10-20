package com.example.silentflow

import android.app.Application
import com.acesso.acessobio_android.onboarding.UnicoSDK
import com.acesso.acessobio_android.onboarding.models.Environment
import com.acesso.acessobio_android.onboarding.models.UnicoSDKConfig

class SilentFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Fire-and-forget: authenticates and warms up the collection on app
        // launch, so it is ready by the time the user reaches the flow.
        UnicoSDK.initializeSDK(
            this,
            UnicoSDKConfig(PocConfig.SDK_KEY, packageName, Environment.UAT),
        )
    }
}

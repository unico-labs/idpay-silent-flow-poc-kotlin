package com.example.silentflow

import com.acesso.acessobio_android.onboarding.AcessoBioConfigDataSource

class UnicoConfig : AcessoBioConfigDataSource {

    override fun getBundleIdentifier(): String {
        return "YOUR_BUNDLE_IDENTIFIER"
    }

    override fun getHostKey(): String {
        return "YOUR_SDK_KEY"
    }
}

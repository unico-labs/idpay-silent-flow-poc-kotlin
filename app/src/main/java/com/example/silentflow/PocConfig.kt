package com.example.silentflow

/**
 * POC-only configuration. In a real integration none of this lives in the app:
 * the company id belongs to the client's backend and the external user id is
 * whatever identifier the client already has for the logged-in user.
 */
object PocConfig {

    const val IDPAY_BASE_URL = "https://transactions.transactional.uat.unico.app"

    const val COMPANY_ID = "YOUR_COMPANY_ID"
}

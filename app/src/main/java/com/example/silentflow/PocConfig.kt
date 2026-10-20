package com.example.silentflow

/**
 * POC-only configuration. In a real integration the company id belongs to the
 * client's backend and the external user id is whatever identifier the client
 * already has for the logged-in user.
 */
object PocConfig {

    // Your mobile SDK Key, registered for this app's applicationId.
    const val SDK_KEY = "YOUR_SDK_KEY"

    const val IDPAY_BASE_URL = "https://transactions.transactional.uat.unico.app"

    const val COMPANY_ID = "YOUR_COMPANY_ID"

    // Margin (ms) after the send confirmation, for the backend to process the
    // collection before the transaction is created.
    const val POST_SEND_WAIT_MS = 1_500L
}

package com.leuwipanjang.ssh

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class LeuwiPanjangApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupSecurityProviders()
    }

    private fun setupSecurityProviders() {
        try {
            // Remove any pre-installed outdated BC provider
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            // Insert modern BouncyCastle at priority position 1
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

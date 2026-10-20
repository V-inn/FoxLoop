package io.github.v_inn.foxloop.console

import android.app.Application
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class ConsoleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android registers a provider named "BC" of its own: a cut-down
        // BouncyCastle missing most of what SSH negotiates (X25519, Ed25519,
        // ChaCha20-Poly1305). sshj asks for algorithms from "BC" by name, so
        // with the platform's in place it finds the provider and then fails
        // inside the first key exchange. Swapping in the full one, before any
        // SSH code runs, is the whole fix.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}

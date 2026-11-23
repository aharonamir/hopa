package com.hopa.feature.settings

import com.hopa.backend.pairing.AdmissionLink
import com.hopa.backend.pairing.AdmissionLinks
import com.hopa.backend.pairing.PairingLink
import com.hopa.backend.pairing.PairingLinks

/**
 * The one field that takes either a whole `hopa://pair…` link or a bare
 * session typed next to a stored gateway. Parsing lives in [PairingLinks]; this
 * names the screen's entry point so the UI and its test agree.
 */
object PairInput {
    fun normalise(text: String, fallbackGatewayUrl: String): PairingLink? =
        PairingLinks.parse(text, fallbackGatewayUrl)

    /**
     * The pending-admission QR a PC shows to a phone: `hopa://pair?v=1&s=…&u=…`.
     * Unlike [normalise], it carries no household and refuses an `h` parameter,
     * so an operator link pasted into the scan screen is never mistaken for one.
     */
    fun admission(text: String): AdmissionLink? = AdmissionLinks.parse(text)
}

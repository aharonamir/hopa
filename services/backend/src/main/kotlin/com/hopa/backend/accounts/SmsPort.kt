package com.hopa.backend.accounts

import org.slf4j.LoggerFactory

/**
 * A transport for a one-time SMS code. The [to] number is already E.164
 * normalized by the caller; an implementation sends the code to it and returns.
 * The code is single-use and expires in minutes, so a lost send is cheap to
 * retry.
 */
interface SmsPort {
    fun send(to: String, code: String)
}

/**
 * The only transport in this deployment: it logs the code at INFO so a local
 * phone can finish the OTP loop without a real SMS provider. The phone number
 * is never logged — it is only ever a login lookup. This is dev-only and must
 * not be enabled in production behind a real carrier.
 */
class LogSmsPort : SmsPort {
    override fun send(to: String, code: String) {
        log.info("SMS OTP code issued: {}", code)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LogSmsPort::class.java)
    }
}

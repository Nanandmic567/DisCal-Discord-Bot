package org.dreamexposure.discal.cam.google

import org.dreamexposure.discal.core.`object`.BotSettings
import org.dreamexposure.discal.core.`object`.google.GoogleCredentialData
import org.dreamexposure.discal.core.`object`.google.InternalGoogleAuthPoll
import org.dreamexposure.discal.core.crypto.AESEncryption
import org.dreamexposure.discal.core.database.DatabaseManager
import org.dreamexposure.discal.core.exceptions.EmptyNotAllowedException
import org.dreamexposure.discal.core.exceptions.google.GoogleAuthCancelException
import org.dreamexposure.discal.core.logger.LOGGER
import org.dreamexposure.discal.core.utils.GlobalVal
import org.dreamexposure.discal.core.wrapper.google.GoogleAuthWrapper
import org.json.JSONObject
import reactor.core.publisher.Mono
import reactor.function.TupleUtils
import java.time.Instant

@Suppress("BlockingMethodInNonBlockingContext")
object GoogleInternalAuthHandler {
    fun requestCode(credNumber: Int): Mono<Void> {
        return GoogleAuthWrapper.requestDeviceCode().flatMap { response ->
            val responseBody = response.body!!.string()
            response.body?.close()
            response.close()

            if (response.code == GlobalVal.STATUS_SUCCESS) {
                val codeResponse = JSONObject(responseBody)

                val url = codeResponse.getString("verification_url")
                val code = codeResponse.getString("user_code")
                LOGGER.debug(GlobalVal.DEFAULT, "[!GDC!] DisCal Google Cred Auth $credNumber", "$url | $code")

                val poll = InternalGoogleAuthPoll(
                        credNumber,
                        interval = codeResponse.getInt("interval"),
                        expiresIn = codeResponse.getInt("expires_in"),
                        remainingSeconds = codeResponse.getInt("expires_in"),
                        deviceCode = codeResponse.getString("device_code"),
                ) { this.pollForAuth(it as InternalGoogleAuthPoll) }

                GoogleAuthWrapper.scheduleOAuthPoll(poll)
            } else {
                LOGGER.debug(GlobalVal.DEFAULT, "Error request access token Status code: ${response.code} | ${response.message}" +
                        " | $responseBody")

                Mono.empty()
            }
        }
    }

    private fun pollForAuth(poll: InternalGoogleAuthPoll): Mono<Void> {
        return GoogleAuthWrapper.requestPollResponse(poll).flatMap { response ->
            val responseBody = response.body!!.string()
            response.body?.close()
            response.close()

            when (response.code) {
                GlobalVal.STATUS_FORBIDDEN -> {
                    //Handle access denied
                    LOGGER.debug(GlobalVal.DEFAULT, "[!GDC!] Access denied for credential: ${poll.credNumber}")

                    Mono.error(GoogleAuthCancelException())
                }
                GlobalVal.STATUS_BAD_REQUEST, GlobalVal.STATUS_PRECONDITION_REQUIRED -> {
                    //See if auth is pending, if so, just reschedule.

                    val aprError = JSONObject(responseBody)
                    when {
                        aprError.optString("error").equals("authorization_pending", true) -> {
                            //Response pending
                            Mono.empty()
                        }
                        aprError.optString("error").equals("expired_token", true) -> {
                            //Token expired, auth is cancelled
                            LOGGER.debug(GlobalVal.DEFAULT, "[!GDC!] token expired.")

                            Mono.error(GoogleAuthCancelException())
                        }
                        else -> {
                            LOGGER.debug(GlobalVal.DEFAULT, "[!GDC!] Poll Failure! Status code: ${response.code}" +
                                    " | ${response.message} | $responseBody")

                            Mono.error(GoogleAuthCancelException())
                        }
                    }
                }
                GlobalVal.STATUS_RATE_LIMITED -> {
                    //We got rate limited... oops. Let's just poll half as often...
                    poll.interval = poll.interval * 2

                    Mono.empty()
                }
                GlobalVal.STATUS_SUCCESS -> {
                    //Access granted, save credentials...
                    val aprGrant = JSONObject(responseBody)
                    val aes = AESEncryption(BotSettings.CREDENTIALS_KEY.get())

                    val refreshMono = aes.encrypt(aprGrant.getString("refresh_token"))
                    val accessMono = aes.encrypt(aprGrant.getString("access_token"))

                    Mono.zip(refreshMono, accessMono).flatMap<GoogleAuthCancelException?>(TupleUtils.function { refresh, access ->
                        val expiresAt = Instant.now().plusSeconds(aprGrant.getLong("expires_in"))

                        val creds = GoogleCredentialData(poll.credNumber, refresh, access, expiresAt)

                        DatabaseManager.updateCredentialData(creds)
                                .then(Mono.error(GoogleAuthCancelException()))
                    }).onErrorResume(EmptyNotAllowedException::class.java) { Mono.error(GoogleAuthCancelException()) }
                }
                else -> {
                    //Unknown network error...
                    LOGGER.debug(GlobalVal.DEFAULT, "[!GDC!] Network error; poll failure Status code: ${response.code} " +
                            "| ${response.message} | $responseBody")

                    Mono.error(GoogleAuthCancelException())
                }
            }
        }.then()
    }
}

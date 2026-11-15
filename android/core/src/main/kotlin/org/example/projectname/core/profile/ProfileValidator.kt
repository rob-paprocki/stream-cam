package org.example.projectname.core.profile

import org.example.projectname.core.validation.FieldError
import org.example.projectname.core.validation.FieldRules
import org.example.projectname.core.validation.Parsed
import org.example.projectname.core.validation.StreamUrls
import org.example.projectname.core.validation.UrlScheme

/** Checks every field of a [DestinationProfile] against [FieldRules]. */
object ProfileValidator {

    /** Returns every problem found, or an empty list when the profile is fine. */
    fun validate(profile: DestinationProfile): List<FieldError> {
        val errors = ArrayList<FieldError>()
        if (profile.schemaVersion != PROFILE_SCHEMA_VERSION) {
            errors += FieldError("schemaVersion", "This app reads profile format $PROFILE_SCHEMA_VERSION, not ${profile.schemaVersion}.")
        }
        errors.check("id", FieldRules.checkProfileId(profile.id))
        errors.check("name", FieldRules.checkProfileName(profile.name))
        errors += validate(profile.destination, prefix = "destination.")
        return errors
    }

    fun validate(destination: Destination, prefix: String = ""): List<FieldError> {
        val errors = ArrayList<FieldError>()
        fun field(name: String) = prefix + name
        when (destination) {
            is Destination.SrtCaller -> {
                errors.check(field("host"), FieldRules.checkHost(destination.host))
                errors.check(field("port"), FieldRules.checkRemotePort(destination.port))
                destination.streamId?.let { errors.check(field("streamId"), FieldRules.checkSrtStreamId(it)) }
                errors.check(field("latencyMs"), FieldRules.checkSrtLatency(destination.latencyMs))
                errors.checkRef(field("passphraseRef"), destination.passphraseRef)
            }
            is Destination.SrtListener -> {
                errors.check(field("port"), FieldRules.checkListenPort(destination.port))
                errors.check(field("latencyMs"), FieldRules.checkSrtLatency(destination.latencyMs))
                errors.checkRef(field("passphraseRef"), destination.passphraseRef)
            }
            is Destination.RtmpPush -> {
                errors.check(field("url"), checkPushUrl(destination.url, UrlScheme.RTMP_ANY))
                errors.checkRef(field("streamKeyRef"), destination.streamKeyRef)
            }
            is Destination.RtspPush -> {
                errors.check(field("url"), checkPushUrl(destination.url, UrlScheme.RTSP_ANY))
                destination.username?.let { errors.check(field("username"), FieldRules.checkUsername(it)) }
                errors.checkRef(field("passwordRef"), destination.passwordRef)
            }
            is Destination.RtspPull -> {
                errors.check(field("port"), FieldRules.checkListenPort(destination.port))
                errors.check(field("path"), FieldRules.checkRtspPath(destination.path))
                destination.username?.let { errors.check(field("username"), FieldRules.checkUsername(it)) }
                errors.checkRef(field("passwordRef"), destination.passwordRef)
            }
            is Destination.RtmpPull -> {
                errors.check(field("port"), FieldRules.checkListenPort(destination.port))
                errors.check(field("app"), FieldRules.checkRtmpApp(destination.app))
                errors.check(field("streamName"), FieldRules.checkRtmpStreamName(destination.streamName))
            }
        }
        return errors
    }

    /**
     * A push URL needs a path (the RTMP app, or the RTSP stream path) and no query. A query is refused
     * because it often carries a key or token, and profile files must not carry secrets.
     */
    private fun checkPushUrl(url: String, schemes: Set<UrlScheme>): String? {
        val parsed = when (val result = StreamUrls.parse(url, schemes)) {
            is Parsed.Invalid -> return result.message
            is Parsed.Valid -> result.value
        }
        if (parsed.hasQuery) {
            return "Remove the part of the address from ? onwards. Keys and passwords go in their own fields."
        }
        if (parsed.pathSegments.isEmpty()) {
            return if (parsed.scheme in UrlScheme.RTMP_ANY) {
                "Add the app name after the host, like ${parsed.scheme.text}://live.example.com/live"
            } else {
                "Add the stream path after the host, like ${parsed.scheme.text}://192.168.100.20:8554/live"
            }
        }
        return null
    }

    private fun MutableList<FieldError>.check(field: String, problem: String?) {
        if (problem != null) add(FieldError(field, problem))
    }

    private fun MutableList<FieldError>.checkRef(field: String, ref: SecretRef?) {
        if (ref != null) check(field, FieldRules.checkProfileId(ref.id)?.let { "The saved-secret link is damaged. Enter the secret again." })
    }
}

package com.thesis.geckowifi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * GeoCertificates as the map server serves them (geopki: pkg/crypto/certificate.go
 * and pkg/extension/wifi; docs/geocert-schema.md). The core binds a `domain` to
 * areas; a WiFi network is in the named extension `extensions.wifi`. Certificates
 * of other kinds (shops, ATMs, ...) are in every answer too, since the proof covers
 * all certificates of an area, but they don't concern GECKO.
 *
 * [decode] maps the server's format onto the app's [GeoCertificate], which keeps
 * the shape of schema v3: the rest of the app and the saved data stay as they were.
 */
object GeoCertificateFormat {
    /** The extension holding the WiFi network. */
    const val WIFI = "wifi"

    /** The version of the wifi extension this client reads. */
    const val WIFI_VERSION = 1

    @Serializable
    private data class Wire(
        @SerialName("certificate_id") val certificateId: String,
        val domain: String = "",
        val areas: List<GeoCertArea> = emptyList(),
        @SerialName("areas_altitude") val areasAltitude: List<List<Double>> = emptyList(),
        @SerialName("not_valid_before") val notValidBefore: String? = null,
        @SerialName("not_valid_after") val notValidAfter: String,
        val critical: List<String> = emptyList(),
        val extensions: Map<String, JsonElement> = emptyMap()
    )

    @Serializable
    private data class WifiExtension(
        val version: Int = 0,
        /** Display name of the operator, never used for matching. */
        val operator: String? = null,
        val ssid: String? = null,
        @SerialName("ssid_shared") val ssidShared: Boolean = false,
        @SerialName("auth_modes") val authModeNames: List<String> = emptyList(),
        val pmf: String? = null,
        val enterprise: EnterpriseIdentity? = null,
        val portal: PortalTLSIdentity? = null
    )

    /**
     * The certificate in [text] as the app's model, or null if it registers no
     * WiFi network (a certificate of another kind). Throws if it is malformed,
     * or its wifi extension is a version this client can't read.
     *
     * `critical` is carried over without "wifi", which this client implements:
     * any other critical extension still makes the certificate unevaluable
     * ([GeoCertificate.isEvaluable]).
     */
    fun decode(json: Json, text: String): GeoCertificate? {
        val wire = json.decodeFromString(Wire.serializer(), text)
        val raw = wire.extensions[WIFI] ?: return null
        val wifi = json.decodeFromJsonElement(WifiExtension.serializer(), raw)
        require(wifi.version == WIFI_VERSION) {
            "wifi extension version ${wifi.version}, this client reads $WIFI_VERSION"
        }
        return GeoCertificate(
            schemaVersion = SCHEMA_VERSION,
            certificateId = wire.certificateId,
            subject = Subject(id = wire.domain, name = wifi.operator),
            critical = wire.critical - WIFI,
            wifi = WiFiIdentity(
                ssid = wifi.ssid,
                ssidShared = wifi.ssidShared,
                authModeNames = wifi.authModeNames,
                pmf = wifi.pmf,
                enterprise = wifi.enterprise
            ),
            portal = wifi.portal,
            areas = wire.areas,
            areasAltitude = wire.areasAltitude,
            notValidBefore = wire.notValidBefore,
            notValidAfter = wire.notValidAfter
        )
    }

    /** What [GeoCertificate.schemaVersion] says for a certificate read by [decode]: same content as v3. */
    const val SCHEMA_VERSION = 3
}

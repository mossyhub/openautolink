package com.openautolink.app.carplay

/** Pure fail-closed export boundary. Never forward arbitrary receiver text to diagnostic sinks. */
object CarPlayAudioDiagnostics {
    const val MAX_INPUT_LENGTH = 384
    const val MAX_LINE_LENGTH = 500
    private const val number = "[0-9]{1,19}"
    private const val optional = "($number|null)"
    private const val bool = "(true|false)"
    // Negotiated engine values are lowercased; offer rows use the protocol spelling.
    private const val audioType = "(compatibility|default|alert|media|telephony|speechRecognition|speechrecognition)"
    private const val codec = "(LPCM|OPUS|AAC_LC|AAC_ELD)"
    private const val failure = "(IOException|EOFException|SocketException|SocketTimeoutException|IllegalArgumentException|IllegalStateException|SecurityException|CodecException)"
    private val accepted = Regex("BENCH_DIAG streamReply type=([0-9]{1,3}) port=$optional streamId=$optional")
    // Full-string matches: unknown fields, suffixes, control characters and upstream free text fail closed.
    private val schemas = listOf(
        "CarPlay USB stage=(permission|usb-permission|replacement-permission|vpn-consent|reenumeration|usbmux|phone-identification|carkit|listener|endpoint|runtime) result=(pending|ready|accepted|sent)",
        "CarPlay USB stage=failed reason=(PERMISSION|TIMEOUT|SETUP|PASSWORD_PROTECTED|USER_DENIED|INVALID_RECORD|TRANSPORT_LOSS|TRUST_PENDING_TIMEOUT|CANCELLED|REMOTE_ERROR|INVALID_RESPONSE)",
        "CarPlay USB pairing stage=(connecting|host-buid|device-key|wifi-address|record-prepared|pair-requested|trust-pending|paired)",
        "CarPlay USB pairing-error code=(InvalidPairRecord|MissingPairRecord|InvalidHostID)",
        "CarPlay USB stage=retry result=blocked",
        "CarPlay USB stage=(usb-permission|replacement-permission) result=(granted|denied|detached|timed_out|cancelled)",
        "carplay-metadata received=$number media=$bool guidance=$bool hostPublication=$bool",
        "carplay-metadata hostPublication=true media=$bool guidance=$bool",
        "carplay-metadata publication=failed",
        "CarPlay connection stage=(permissions|bluetooth|selected-bond|receiver-identity|interface|wifi-profile|network-selection|password|identity-provisioning|rfcomm|bootstrap-identification|bootstrap-authentication|enrollment|listener|endpoint|runtime) result=(started|passed|failed)( type=$failure reason=(SETUP|NETWORK_SELECTION|BOOTSTRAP_IDENTIFICATION|BOOTSTRAP_AUTHENTICATION|WIRELESS_REQUEST_TIMEOUT|LISTENER_BIND|ADMISSION_POLICY|RUNTIME_IDENTIFICATION|RUNTIME_AUTHENTICATION|RUNTIME_TRANSPORT|RUNTIME_STARTUP_TIMEOUT))?",
        "CarPlay network stage=address-selection-failed type=$failure",
        "CarPlay network stage=address-selected family=(4|6) mode=(shared|ap); ingress unverified",
        "CarPlay network stage=listener-bound family=(0|4|6) admission=(trusted-controller|manual-peer); ingress unverified",
        "CarPlay network stage=bind-failed type=$failure",
        "CarPlay network stage=admission-unavailable reason=(explicit-enrollment-authorization-required|existing-controller-required)",
        "CarPlay network stage=accept-declined reason=peer-interface-scope",
        "CarPlay network stage=control-accepted authentication=pending",
        "CarPlay network stage=authentication accepted=$bool",
        "CarPlay network stage=activation-skipped reason=trusted-control-peer-pending; phone initiation unverified",
        "CarPlay RFCOMM connecting · timeout 20s",
        "CarPlay RFCOMM connected · authentication pending",
        "CarPlay phone identification accepted",
        "CarPlay receiver MFi authentication accepted · phone AirPlay trust pending",
        "CarPlay endpoint listening · awaiting real phone / render evidence",
        "CarPlay endpoint reply id=(19980|19981|19982|19983|19984) result=sent",
        "CarPlay bootstrap retired · authenticated wireless runtime retained; HFP unchanged",
        "control discovery (started|stopped|failed code=[0-9]{1,10}|stop failed code=[0-9]{1,10})",
        "control (resolution unavailable|operation failed)",
        "control endpoint declined selectedPeer=$bool validPort=$bool",
        "control resolved family=(IPv4|IPv6) port=[0-9]{1,5}",
        "control connecting attempt=(1|2) port=[0-9]{1,5}",
        "control (request sent|transport failed) attempt=(1|2)",
        "control response attempt=(1|2) status=[0-9]{3}; no projection proof",
        "media stage=(admission-declined|listener-failed|runtime-identification-accepted|runtime-authentication-accepted|runtime-endpoint-reply-unavailable)",
        "media stage=runtime-control-failed type=$failure",
        "media stage=(runtime-message-received|runtime-subscriptions-sent|video-first-frame-confirmed) count=$number",
        "carplay-car-data id=(0|65530|65531|65532|41216|41217|41218) reason=(authenticated|retired|unauthenticated|started|stopped|disabled|unsupported_request|source_absent|transmitted|send_failed) count=$number fields=([0-9]|[12][0-9]|3[01]) suppressed=$number",
        "carplay-car-data-offer location=$bool range=$bool current=$bool maximum=$bool",
        "carplay-car-data-request id=(65530|41216) bytes=[0-9]{1,5} result=(accepted|disabled|malformed|oversized|duplicate|unsupported_id|width|interval_bounds|orphan_interval|no_supported_groups) parameter=[0-9]{1,5} length=[0-9]{1,3} groups=[0-7] ggaMs=[0-9]{1,5} rmcMs=[0-9]{1,5}",
        "carplay-car-data-source property=(range|current|maximum) reason=(available|absent|fuel|metadata|generation|source|inactive|status|timestamp|source_age|receipt_age|bounds|inconsistent) status=(-1|[0-9]) active=$bool generation=$bool sourceAgeMs=(-1|[0-9]{1,8}) receiptAgeMs=(-1|[0-9]{1,8})",
        "carplay-car-data-bootstrap id=(65530|65532|41216|41218) result=(deferred|oversized|retired)",
        "carplay-appearance reason=(transmitted|send_failed) count=$number",
        "carplay-enrollment stage=commit accepted=$bool",
        "carplay-enrollment stage=awaiting-selected-bootstrap-request",
        "carplay-enrollment stage=authorized windowMs=120000 boundary=(selected-bootstrap-request|explicit-manual-peer); first-client race remains possible",
        "BENCH_DIAG streamType=[0-9]{1,3} audioFormat=$optional hasConnectionId=$bool hasSeed=$bool",
        "BENCH_DIAG audioOfferRow index=[0-9]{1,2} type=[0-9]{1,3} audioType=$audioType output=$number input=($number|absent)",
        "BENCH_DIAG audioOfferRows=[0-9]{1,3} codecBits=($number(,$number){0,31})?",
        "BENCH_DIAG modeResource=(1|2) entity=(-?[0-9]{1,10}|null) permanent=(-?[0-9]{1,10}|null)",
        "BENCH_DIAG streamOfferCount=[0-9]{1,4} acceptedCount=[0-9]{1,4}",
        "BENCH_DIAG responseStatus=[0-9]{3}",
        "BENCH_DIAG handleFailureType=$failure at=([0-9]{1,6}|null)",
        "BENCH_DIAG requestMethod=(SETUP|RECORD|TEARDOWN|GET|POST|OPTIONS|GET_PARAMETER|SET_PARAMETER) bytes=[0-9]{1,10}",
        "BENCH_DIAG incomingCommand=(disableBluetooth|modesChanged|requestUI|duckAudio|unduckAudio|other)",
        "BENCH_DIAG videoOutput codec=(H264|H265) advertised=[0-9]{1,5}x[0-9]{1,5} coded=[0-9]{1,5}x[0-9]{1,5} visible=[0-9]{1,5}x[0-9]{1,5}",
        "BENCH_DIAG displayOffer codec=H264 width=[0-9]{1,5} height=[0-9]{1,5} fps=[0-9]{1,3} fallback=$bool",
        "BENCH_DIAG (controlPeerEOF|eventPeerEOF|eventConnected)",
        "BENCH_DIAG microphoneCapability=$bool",
        "BENCH_DIAG recordTiming synced=$bool matchedResponses=[0-9]{1,10}",
        "BENCH_DIAG startupRequestUiSent=$bool",
        "media stage=receive kind=audio type=(100|101|102) packets=$number bytes=$number missing=$number gaps=$number late=$number maxGap=$number readMs=$number arrivalMs=$number processMs=$number longReads=$number ended=$bool",
        "media stage=receive kind=video type=110 packets=$number bytes=$number missing=$number gaps=$number late=$number maxGap=$number readMs=$number arrivalMs=$number processMs=$number longReads=$number ended=$bool",
        "media stage=audio-setup stream=$number type=(100|101|102) audioType=$audioType codec=$codec rate=$number clock=$number channels=(1|2) spf=$number",
        "media stage=audio-track stream=$number type=(100|101|102) audioType=$audioType codec=$codec rate=$number channels=(1|2) capacity=$number",
        "media stage=audio-stats stream=$number type=(100|101|102) audioType=$audioType codec=$codec packets=$number queue=$number queueDrops=$number decoderDrops=$number written=$number zero=$number partial=$number errors=$number droppedBytes=$number underruns=$optional route=$optional ended=$bool",
        "carplay-nav kind=(route|maneuver|lifecycle) parsed=$number failed=$number result=(parsed|failed|rejected|cleared) state=(unknown|active|stopped|arrived) current=(absent|empty|nonempty) cached=$bool publishable=$bool transition=(none|start|change|clear) reason=(none|stop|arrived|empty|cache_miss|stale|revoke)",
        "carplay-nav publication=returned guidance=$bool transition=(start|change|clear)",
        "media stage=(audio-decoder-configured|audio-output-failed) codec=$codec",
        "media stage=audiotrack-written bytes=$number; human audibility unverified",
        "media stage=(audio-focus-change|audio-focus-granted|audio-focus-denied|audio-owner-limit|audio-retirement-pending|audio-queue-overflow|audio-decoder-backpressure|audio-write-backpressure|microphone-owner-busy|session-ended)",
        "media stage=audio-owners scope=own owners=[0-8] tracks=[0-8]",
        "media stage=audio-route stream=$number type=(100|101|102) audioType=$audioType codec=$codec scope=own usage=([0-9]{1,4}|null) content=([0-9]{1,4}|null) device=([0-9]{1,4}|null)",
        "media stage=device-playback-summary scope=device owner=unknown result=(ok|unavailable) observed=[0-9]{1,4} emitted=[0-8] truncated=$bool",
        "media stage=device-playback index=[0-7] scope=device owner=unknown usage=([0-9]{1,4}|null) content=([0-9]{1,4}|null) device=([0-9]{1,4}|null)",
        "media stage=status-queue drops=[0-9]{1,19}",
        "media stage=record-active; rendering unverified",
        "media stage=microphone-format type=100 audioType=(default|telephony|speechrecognition) codec=LPCM rate=24000 channels=1 spf=480 source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION)",
        "media stage=microphone-format type=100 audioType=(default|telephony|speechrecognition) codec=OPUS rate=48000 channels=1 spf=(480|960) source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION)",
        "media stage=audio-focus-change change=(-3|-2|-1|1) volumePercent=(0|20|100)",
        "media stage=media-control index=[1-5] outcome=(sent|failed)",
        "media stage=microphone-capture type=100 audioType=(default|telephony|speechrecognition) frames=$number nonzeroFrames=$number state=(1|3|unknown) ended=$bool",
        "media stage=microphone-policy scope=own source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION) rate=(24000|48000) permission=(granted|denied|unknown) mute=(true|false|unknown) route=(builtin|wired|usb|bluetooth|other|none|unknown) silenced=(true|false|unknown) result=(ok|partial|unavailable)",
        "media stage=microphone-started type=100; uplink audibility unverified",
        "media stage=microphone-datagram-sent packets=$number; phone receipt unverified",
        "media stage=microphone-failed type=100 errorType=$failure",
        "media stage=(microphone-worker-start-failed|microphone-queue-overflow|microphone-retirement-pending)",
    ).map(::Regex)

    fun sanitize(session: Long, message: String): String? {
        if (session <= 0 || message.length > MAX_INPUT_LENGTH) return null
        val receipt = accepted.matchEntire(message)
        val safe = if (receipt != null) "BENCH_DIAG streamAccepted type=${receipt.groupValues[1]}"
            else message.takeIf { candidate -> schemas.any { it.matches(candidate) } } ?: return null
        return "CARPLAY_AUDIO session=$session $safe".takeIf { it.length <= MAX_LINE_LENGTH }
    }
}

/** Invoke on ProjectionReceiver's existing bounded status executor, never from media/control workers. */
class CarPlayAudioDiagnosticReporter(
    private val session: Long,
    private val clockNs: () -> Long,
    private val sink: (String) -> Unit,
) {
    private var windowNs: Long? = null
    private var emitted = 0
    private var suppressed = 0L

    fun record(message: String) {
        val line = CarPlayAudioDiagnostics.sanitize(session, message) ?: return
        val dropped: Long = synchronized(this) {
            val now = clockNs()
            val start = windowNs
            var previous = 0L
            if (start == null || now - start >= 1_000_000_000L) {
                windowNs = now; emitted = 0; previous = suppressed; suppressed = 0
            }
            if (emitted >= MAX_EVENTS_PER_SECOND) {
                if (suppressed < Long.MAX_VALUE) suppressed++
                return
            }
            emitted++
            previous
        }
        if (dropped > 0) runCatching { sink("CARPLAY_AUDIO session=$session diagnosticsSuppressed=$dropped") }
        runCatching { sink(line) }
    }

    companion object { const val MAX_EVENTS_PER_SECOND = 64 }
}

package com.openautolink.app.carplay

/** Pure fail-closed export boundary. Never forward arbitrary receiver text to diagnostic sinks. */
object CarPlayAudioDiagnostics {
    const val MAX_INPUT_LENGTH = 384
    const val MAX_LINE_LENGTH = 1152
    private const val MAX_NUMERIC_INPUT_LENGTH = 1024
    private const val number = "[0-9]{1,19}"
    private const val optional = "($number|null)"
    private const val bool = "(true|false)"
    private const val micRequestedSource = "(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION)"
    private const val micObservedSources = "DEFAULT|MIC|VOICE_UPLINK|VOICE_DOWNLINK|VOICE_CALL|CAMCORDER|VOICE_RECOGNITION|VOICE_COMMUNICATION|REMOTE_SUBMIX|UNPROCESSED|VOICE_PERFORMANCE"
    private const val micMissing = "unknown|unavailable|absent"
    private const val micRate = "(8000|16000|24000|32000|44100|48000|96000|192000|$micMissing)"
    private const val micChannels = "(mono|stereo|$micMissing)"
    private const val micEncoding = "(pcm8|pcm16|float|$micMissing)"
    private const val micEffectCount = "([0-9]|1[0-6])"
    private const val btState = "(disconnected|connecting|connected|disconnecting|stopped|started|off|turning_off|on|turning_on|unknown)"
    // Negotiated engine values are lowercased; offer rows use the protocol spelling.
    private const val audioType = "(compatibility|default|alert|media|telephony|speechRecognition|speechrecognition)"
    private const val codec = "(LPCM|OPUS|AAC_LC|AAC_ELD)"
    private const val failure = "(IOException|EOFException|SocketException|SocketTimeoutException|IllegalArgumentException|IllegalStateException|SecurityException|CodecException)"
    private const val networkClass = "next=(tcp|icmpv6|udp|none|unknown) icmp=(ns|na|unknown) flags=([0-7]|1[6-9]|2[0-3]|unknown)"
    private const val networkAttempt = "([1-9]|1[0-6])"
    private const val packetBytes = "([0-9]{1,4}|[0-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])"
    private const val ledgerEvent = "([1-9]|[1-9][0-9]|1[0-8][0-9]|19[0-3])"
    private const val pcmCount = "(?:[0-9]|[1-9][0-9]{1,2}|1[0-9]{3}|20[0-3][0-9]|204[0-8])"
    private const val pcmAmplitude = "(?:[0-9]|[1-9][0-9]{1,3}|[12][0-9]{4}|3[01][0-9]{3}|32[0-6][0-9]{2}|327[0-5][0-9]|3276[0-8])"
    private const val pcmChannels = "ch0Samples=$pcmCount ch0Nonzero=$pcmCount ch0Peak=$pcmAmplitude ch0Rms=$pcmAmplitude ch0Clip=$pcmCount ch1Samples=$pcmCount ch1Nonzero=$pcmCount ch1Peak=$pcmAmplitude ch1Rms=$pcmAmplitude ch1Clip=$pcmCount"
    private val accepted = Regex("BENCH_DIAG streamReply type=([0-9]{1,3}) port=$optional streamId=$optional")
    // Full-string matches: unknown fields, suffixes, control characters and upstream free text fail closed.
    private const val usbByte = "(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])"
    private val schemas = listOf(
        "media stage=audio-pcm stream=$number type=(100|101|102) audioType=(default|media|telephony|speechrecognition|alert|compatibility) codec=$codec purpose=(media|call|siri|ring|nav) phase=(decoded|accepted) event=(start|periodic|terminal) window=$number rate=(8000|16000|24000|32000|44100|48000) channels=(1|2) observed=$number scanned=$pcmCount tail=$number limit=2048 coverage=(full|prefix) $pcmChannels queueDrops=$number",
        "media stage=microphone-pcm receiver=$number event=$ledgerEvent owner=$number boundary=(raw|converted) phase=(first|ended|released) rate=(24000|48000) channels=(1|2) observed=$number scanned=$pcmCount tail=$number limit=2048 coverage=(full|prefix) $pcmChannels",
        "media stage=microphone-ledger receiver=$number event=$ledgerEvent owner=$number history=(manual|normal) kind=(receiver|probe|live) action=(snapshot|inputs|recorder-cache|encoder-cache|decoder-cache|create|buffer|validate|start|end|stop|release-begin|released|release-failed|retired|limit) result=(attempt|ok|failed|hit|miss|absent|blocked|cancelled|dropped) source=(NONE|MIC|VOICE_RECOGNITION|VOICE_COMMUNICATION) rate=(0|24000|48000) channels=[0-2] bytes=[0-9]{1,10} elapsedUs=$number",
        "media stage=microphone-signal receiver=$number event=$ledgerEvent owner=$number blocks=$number nonzeroBlocks=$number partialBytes=[0-9]{1,10} reason=(cancelled|permission|failed|ineligible) phase=(ended|released) elapsedUs=$number",
        "CarPlay USB configuration found=$bool appleEthernet=$bool transitions=[01] decision=(reuse-descriptors|reuse-without-apple-ethernet|request-transition|reject-missing-configuration)",
        "CarPlay USB transition attempt=1 result=(accepted|rejected) fallback=$bool",
        "CarPlay USB selection configurationId=$usbByte usbmuxInterfaceId=$usbByte usbmuxAlt=$usbByte ncmControlInterfaceId=$usbByte ncmControlAlt=$usbByte ncmDataInterfaceId=$usbByte ncmDataAlt=$usbByte",
        "USB network stage=packet direction=(in|out) packetBytes=$packetBytes $networkClass peerMacKnown=$bool decision=(forward|filter-drop|mac-unknown-drop) filterDrops=([0-9]|[12][0-9]|3[0-2]) macUnknownDrops=([0-9]|[12][0-9]|3[0-2]) sinceOpenUs=$number",
        "USB network stage=nd-receipt receipt=[1-8] icmp=(ns|na) shape=(complete|truncated|invalid) source=(unspecified|specified) target=(host|other|unknown) hopLimit=(valid|invalid) checksum=(valid|invalid|unknown) tunWrite=(written|failed)",
        "USB network stage=send-start attempt=$networkAttempt packetBytes=$packetBytes $networkClass startMonoNs=$number",
        "USB network stage=send-end attempt=$networkAttempt packetBytes=$packetBytes $networkClass result=(full|failed|short) transferred=-?[0-9]{1,10} blockBytes=$packetBytes timeoutMs=[0-9]{1,10} startMonoNs=$number elapsedUs=$number sinceOpenUs=$number",
        "NCM startup attempt=$number timeoutMs=[0-9]{1,10} transferred=-?[0-9]{1,10} blockBytes=[0-9]{1,10} ready=[01] writeStartMonoNs=$number writeElapsedUs=$number sinceOpenUs=$number",
        "CarPlay USB stage=endpoint result=written requestMonoNs=$number writtenReceiptMonoNs=$number writeAwaitElapsedUs=$number",
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
        "CarPlay network stage=control-accepted authentication=pending( acceptedMonoNs=$number)?",
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
        "media stage=(presentation-attached|presentation-detached) generation=$number",
        "media stage=presentation-rendered generation=$number codecGeneration=$number",
        "media stage=(surface-lost|video-retired)",
        "media stage=video-config action=(accepted|duplicate) receipt=$number count=$number codec=(H264|H265)",
        "media stage=video-reconfigure reason=(presentation|config-change|missing-codec) receipt=$number count=$number",
        "media stage=video-recovery reason=(presentation|config-change|queue-reset|unanchored|input-timeout|input-size|decoder-failed) receipt=$number count=$number",
        "media stage=runtime-control-failed type=$failure",
        "media stage=(runtime-message-received|runtime-subscriptions-sent|video-first-frame-confirmed) count=$number",
        "carplay-car-data id=(0|65530|65531|65532|41216|41217|41218) reason=(authenticated|retired|unauthenticated|started|stopped|disabled|unsupported_request|source_absent|transmitted|send_failed) count=$number fields=([0-9]|[1-5][0-9]|6[0-3]) suppressed=$number",
        "carplay-car-data-offer location=$bool range=$bool current=$bool maximum=$bool( temperature=true)?",
        "carplay-car-data-request id=(65530|41216) bytes=[0-9]{1,5} result=(accepted|disabled|malformed|oversized|duplicate|unsupported_id|width|interval_bounds|orphan_interval|no_supported_groups) parameter=[0-9]{1,5} length=[0-9]{1,3} groups=([0-9]|1[0-5]) ggaMs=[0-9]{1,5} rmcMs=[0-9]{1,5}",
        "carplay-car-data-source property=(range|current|maximum|temperature) reason=(available|absent|fuel|metadata|generation|source|inactive|status|timestamp|source_age|receipt_age|bounds|inconsistent) status=(-1|[0-9]) active=$bool generation=$bool sourceAgeMs=(-1|[0-9]{1,8}) receiptAgeMs=(-1|[0-9]{1,8})",
        "carplay-car-data-bootstrap id=(65530|65532|41216|41218) result=(deferred|oversized|retired)",
        "carplay-appearance reason=(transmitted|send_failed) count=$number",
        "carplay-restriction reason=(absent|metadata|source|generation|inactive|status|timestamp|stale|not_park|actual_park|user_override) limited=$bool result=(transmitted|send_failed) count=$number",
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
        "carplay-nav kind=(route|maneuver|lifecycle) parsed=$number failed=$number result=(parsed|failed|rejected|cleared) state=(unknown|active|stopped|arrived) current=(absent|empty|nonempty) cached=$bool publishable=$bool transition=(none|start|change|clear) reason=(none|stop|arrived|empty|cache_miss|stale|expired|revoke)",
        "carplay-nav-cache epoch=$number size=[0-8] selected=$bool inserts=$number replacements=$number evictions=$number eviction=(none|oldest)",
        "carplay-nav publication=returned guidance=$bool transition=(start|change|clear)",
        "carplay-route publication=returned active=$bool instruction=(ready|loading|none) transition=(start|change|end)",
        "media stage=(audio-decoder-configured|audio-output-failed) codec=$codec",
        "media stage=audiotrack-written bytes=$number; human audibility unverified",
        "media stage=(audio-focus-change|audio-focus-granted|audio-focus-denied|audio-owner-limit|audio-retirement-pending|audio-queue-overflow|audio-decoder-backpressure|audio-write-backpressure|microphone-owner-busy|session-ended)",
        "media stage=audio-focus-owner purpose=(media|call|ring|assistant) action=(request|abandon|pending) usage=(1|2|6|16) content=(1|2|4) gain=(1|2) revision=$number",
        "media stage=audio-focus-request reason=(stream-start|user-play) result=(granted|denied|retained|closed) revision=$number volumePercent=(0|20|100) mute=(permanent-loss|transient|denied|duck|unmuted)",
        "media stage=audio-mute reason=(stream-start|focus-change) revision=$number volumePercent=(0|20|100) mute=(permanent-loss|transient|denied|duck|unmuted)",
        "media stage=audio-volume stream=$number type=(100|101|102) audioType=$audioType codec=$codec scope=own requestedPercent=(0|20|100) effectivePercent=(0|20|100|null) mute=(permanent-loss|transient|denied|duck|unmuted) revision=$number",
        "media stage=audio-owners scope=own owners=[0-8] tracks=[0-8]",
        "media stage=audio-route stream=$number type=(100|101|102) audioType=$audioType codec=$codec scope=own usage=([0-9]{1,4}|null) content=([0-9]{1,4}|null) device=([0-9]{1,4}|null)",
        "media stage=device-playback-summary scope=device owner=unknown result=(ok|unavailable) observed=[0-9]{1,4} emitted=[0-8] truncated=$bool",
        "media stage=device-playback index=[0-7] scope=device owner=unknown usage=([0-9]{1,4}|null) content=([0-9]{1,4}|null) device=([0-9]{1,4}|null)",
        "media stage=status-queue drops=[0-9]{1,19}",
        "media stage=record-active; rendering unverified",
        "media stage=microphone-format type=100 audioType=(default|telephony|speechrecognition) codec=LPCM rate=24000 channels=1 spf=480 source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION)",
        "media stage=microphone-format type=100 audioType=(default|telephony|speechrecognition) codec=OPUS rate=48000 channels=1 spf=(480|960) source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION)",
        "media stage=audio-focus-change change=(-3|-2|-1|1) volumePercent=(0|20|100)",
        "media stage=media-command-ingress command=(PLAY|PAUSE|STOP|NEXT|PREVIOUS) controller=(bluetooth|car-service|system-ui|oem|other|unknown) sequence=$number elapsedMs=$number",
        "media stage=media-command-queue index=[0-5] outcome=(accepted|deferred|declined) reason=(admitted|queue-full|stale-owner|invalid-index)",
        "media stage=media-play-disposition result=(granted|retained) transport=permitted output=permitted",
        "media stage=media-play-disposition result=(deferred|interrupted) transport=permitted output=withheld",
        "media stage=media-play-disposition result=(denied|closed|stale-owner) transport=withheld output=withheld",
        "media stage=audio-mute reason=stream-start revision=$number volumePercent=0 mute=deferred",
        "media stage=audio-volume stream=$number type=(100|102) audioType=$audioType codec=$codec scope=own requestedPercent=0 effectivePercent=(0|null) mute=deferred revision=$number",
        "media stage=media-focus-recovery reason=user-play result=(permitted|denied|stale-owner)",
        "BENCH_DIAG bluetoothHandoff requested=true policy=observe-only( handling=(unsupported-native-profile-handover|own-bootstrap-only))?",
        "carplay-bt event=(hfp-client|a2dp-sink) state=(disconnected|connecting|connected|disconnecting|unknown) previous=(disconnected|connecting|connected|disconnecting|unknown) scope=device owner=unknown",
        "carplay-bt event=hfp-audio state=(disconnected|connecting|connected|unknown) previous=(disconnected|connecting|connected|unknown) scope=device owner=unknown",
        "carplay-bt event=hfp-voice state=(started|stopped|unknown) previous=unknown scope=device owner=unknown",
        "carplay-bt event=adapter state=(on|off|turning-on|turning-off|unknown) previous=unknown scope=device owner=unknown",
        "media stage=bluetooth-event scope=device owner=unknown event=(hfp-connection|a2dp-connection|hfp-audio|hfp-voice|adapter) state=$btState previous=$btState epoch=$number",
        "media stage=bluetooth-observer scope=device owner=unknown enabled=$bool result=(attached|unavailable|disabled) epoch=$number",
        "media stage=media-control index=[1-5] outcome=(sent|failed)",
        "media stage=microphone-capture type=100 audioType=(default|telephony|speechrecognition) frames=$number nonzeroFrames=$number state=(1|3|unknown) ended=$bool",
        "media stage=microphone-policy scope=own source=(MIC|VOICE_COMMUNICATION|VOICE_RECOGNITION) rate=(24000|48000) permission=(granted|denied|unknown) mute=(true|false|unknown) route=(builtin|wired|usb|bluetooth|other|none|unknown) silenced=(true|false|unknown) result=(ok|partial|unavailable)",
        "media stage=microphone-config scope=own requested=$micRequestedSource rate=(24000|48000) recorder=($micObservedSources|unknown|unavailable) mode=(normal|ringtone|call|communication|unknown|unavailable) config=(present|absent|unavailable) client=($micObservedSources|$micMissing) active=($micObservedSources|$micMissing) clientRate=$micRate deviceRate=$micRate clientChannels=$micChannels deviceChannels=$micChannels clientEncoding=$micEncoding deviceEncoding=$micEncoding route=(builtin|wired|usb|bluetooth|bus|none|$micMissing)",
        "media stage=microphone-effects scope=own source=$micRequestedSource rate=(24000|48000) chain=(client|capture) result=(ok|partial|truncated|absent|unavailable) examined=$micEffectCount truncated=$bool aec=(true|false|unknown) ns=(true|false|unknown) agc=(true|false|unknown) other=$micEffectCount",
        "media stage=microphone-experiment source=(MIC|VOICE_RECOGNITION|VOICE_COMMUNICATION) captureRate=(24000|48000) captureChannels=(1|2) selection=(AVERAGE|FIRST|SECOND) wireRate=(24000|48000)",
        "media stage=microphone-experiment-rejected codec=(AUTO|LPCM|OPUS) reason=no-qualified-input",
        "media stage=microphone-started type=100; uplink audibility unverified",
        "media stage=microphone-datagram-sent packets=$number; phone receipt unverified",
        "media stage=microphone-failed type=100 errorType=$failure",
        "media stage=(microphone-worker-start-failed|microphone-queue-overflow|microphone-retirement-pending)",
    ).let { base ->
        // Reuse ONLY these exact full-string finite policy/config/effect schemas, never arbitrary
        // nested receiver text. Their owning recorder is now retained across delayed delivery.
        base + base.filter { it.startsWith("media stage=microphone-policy ") ||
            it.startsWith("media stage=microphone-config ") || it.startsWith("media stage=microphone-effects ") }
            .map { "media stage=microphone-owned receiver=$number event=$ledgerEvent owner=$number elapsedUs=$number $it" }
    }.map(::Regex)

    fun sanitize(session: Long, message: String): String? {
        val maximum = if (message.startsWith("media stage=audio-pcm ") || message.startsWith("media stage=microphone-pcm ") ||
            message.startsWith("media stage=microphone-owned "))
            MAX_NUMERIC_INPUT_LENGTH else MAX_INPUT_LENGTH
        if (session <= 0 || message.length > maximum) return null
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
    private var networkCompletions = 0
    private var neighborReceipts = 0
    private var networkFirstFull = false
    // Constant-space allowance independent of ordinary per-second details. Each exact runtime
    // can retain four receiver ledgers; unknown extra receivers fail closed, not grow a map.
    private val ledgerReceivers = linkedMapOf<Long, LongArray>()

    fun record(message: String) {
        val line = CarPlayAudioDiagnostics.sanitize(session, message) ?: return
        val ledger = ledgerReceipt.find(message)
        if (ledger != null) {
            val receiver = ledger.groupValues[1].toLongOrNull() ?: return
            val event = ledger.groupValues[2].toIntOrNull() ?: return
            val admitted = synchronized(this) {
                val seen = ledgerReceivers[receiver] ?: if (ledgerReceivers.size < 4)
                    LongArray(4).also { ledgerReceivers[receiver] = it } else return@synchronized false
                val index = (event-1)/64; val mask = 1L shl ((event-1)%64)
                if (seen[index] and mask != 0L) false else { seen[index] = seen[index] or mask; true }
            }
            if (admitted) runCatching { sink(line) }
            return
        }
        // Completion reservations are lifetime-bounded independently of ordinary detail rate.
        // A busy packet/status window must not erase an already-started USB attempt's result.
        val attempt = networkCompletionAttempt.find(message)?.groupValues?.get(1)?.toInt()
        val neighbor = neighborReceipt.find(message)?.groupValues?.get(1)?.toInt()
        val firstFull = message.startsWith("NCM startup ") && message.contains(" ready=1 ")
        if (attempt != null || neighbor != null || firstFull) {
            val admitted = synchronized(this) {
                if (attempt != null) {
                    val bit = 1 shl (attempt - 1) // Exact sanitizer restricts attempts to 1..16.
                    if (networkCompletions and bit != 0) false
                    else { networkCompletions = networkCompletions or bit; true }
                } else if (neighbor != null) {
                    val bit = 1 shl (neighbor - 1) // Exact sanitizer restricts receipts to 1..8.
                    if (neighborReceipts and bit != 0) false
                    else { neighborReceipts = neighborReceipts or bit; true }
                } else if (networkFirstFull) false
                else { networkFirstFull = true; true }
            }
            if (admitted) runCatching { sink(line) }
            return
        }
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

    companion object {
        const val MAX_EVENTS_PER_SECOND = 64
        private val ledgerReceipt = Regex("^media stage=microphone-(?:ledger|signal|pcm|owned) receiver=([0-9]+) event=([0-9]+) ")
        private val networkCompletionAttempt = Regex("^USB network stage=send-end attempt=([0-9]+) ")
        private val neighborReceipt = Regex("^USB network stage=nd-receipt receipt=([1-8]) ")
    }
}

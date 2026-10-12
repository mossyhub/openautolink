# Working DiPlay media negotiation control

Owner-private capture: build/diplay-control-20261009-122418.log. Same TCL/iPhone/home network, exact official DiPlay0.2.13, OAL force-stopped. Raw captures remain ignored and must not be published.

Observed actual audio SETUP: type102, audioType media, audioFormat8388608 (0x800000; AAC-LC48kHz stereo), spf1024, audioLatencyMs1000. No microphone or vocoder parameters in this media SETUP. Stream accepted; authenticated RTP, decoded PCM and AudioTrack start followed.

Audio statistics report routeType2 (speaker), advancing playback head and over1.4million written PCM frames with no reported input drops/underruns during this capture. Owner confirmed music is audible through the TCL in this exact run, backing the authenticated stream, decoded PCM and advancing speaker playback evidence.

OAL already advertises this exact AAC-LC output format, so absence of a matching codec offer does not explain the no-SETUP failure. Missing PCM compatibility rows or other declaration differences could still influence whole-catalog acceptance; this control does not establish such causality.

DiPlay remains active for owner confirmation. No OAL production/Play release occurred.

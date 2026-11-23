# Paddock voice — design

Paddock's own voice, written from scratch. LXST (CC BY-NC-ND) and LXST-kt (Columba only) were
read to learn how a Reticulum voice call behaves; none of their code, structure or wire format
is used. Paddock voice talks only to Paddock.

## Phase 1 — call one rider

**Who may call.** Only a rider who shares a room with me. The callee checks the caller's
identity against the members of its rooms; anyone else is refused without ringing.

**Destination.** Each rider has a second inbound destination, `paddock/voice`, next to its LXMF
one, announced with it. Nothing public is in the announce.

**Signalling.** The caller opens one RNS Link to the callee's voice destination and identifies
on it. Everything then travels over that Link as small packets: a one-byte type and a short
msgpack body. Types: `offer` (caller's name, room, codec), `ringing`, `accept`, `decline`,
`busy`, `bye`, `mute` (shown to the other side). A torn-down Link also ends the call.
Timeouts: 10 s to get `ringing`, 45 s of ringing, 60 s overall with the path request.
One call at a time; a ride's position sharing goes on during a call.

**Media.** Opus through Android's own MediaCodec (BSD, on every phone from Android 10; the app
needs 14). 16 kHz mono, VOIP, about 16 kbps, 20 ms frames. Two frames per Link packet (40 ms),
so the relay sees 25 packets a second, not 50. Each packet carries a 16-bit sequence and the
first frame's 16-bit timestamp, so the receiver can reorder, spot losses and keep time.

**Receive.** Jitter buffer that adapts: starts at 120 ms, grows to at most 400 ms when packets
arrive late, shrinks slowly when the network is calm. A lost frame is concealed by Opus's own
loss concealment where the decoder offers it, else by a short fade, never by a click.
Playback on AudioTrack, USAGE_VOICE_COMMUNICATION.

**Send.** AudioRecord with VOICE_COMMUNICATION, plus Android's AcousticEchoCanceler,
NoiseSuppressor and AutomaticGainControl where the phone has them: the platform's tuned DSP
instead of our own filters. Audio mode IN_COMMUNICATION during the call; a Bluetooth helmet
headset is chosen with setCommunicationDevice when one is connected.

**Python and Kotlin.** Python only carries bytes: it owns the destination and the Link. Kotlin
owns audio, codec and timing. Outbound packets are handed to Python as they are made (25 a
second); inbound packets wait in a queue that Kotlin drains every 20 ms. The 1-second state
poll is not used for media.

**Screen and notification.** Tap a rider in the room: Call. Incoming: a notification with
Answer and Decline and the ringtone, and the call screen inside Paddock (name, time, mute,
speaker/helmet, hang up).

**Screen off.** Android lets an app use the microphone in the background only from a foreground
service of type *microphone*. The app's module keep-alive is connectedDevice|location, so a
call needs a new host call in the module contract (47) and a new MOTO-HUB release. Until then a
call works only with MOTO-HUB on screen.

## Phase 2 — group radio (push-to-talk)

One channel per room. Hold to talk; everyone in the room who is online hears it. The talker
sends the same packets on a Link to each online member (5 riders: 4 streams of 16 kbps). A
`talk`/`over` pair tells the others who holds the channel; a second talker who presses at the
same moment gets a short busy tone, earliest `talk` wins. Each receiver has one decoder and
one jitter buffer per talker. Volume and the TFT show who is talking.

## Relays

Voice is only as good as the relay. rns.kin.earth (the old fixed default) was measured on
2026-11-22 at 290 ms, holding new announces back for minutes and passing about one voice packet
in five; noDNS1 passed every packet at 25 a second, with a path in one second. Paddock therefore
defaults to "auto": at each start it times the public relays in `SEED_RELAYS` and connects to
the three fastest at once, with Reticulum's interface discovery adding the relays it hears of.
Riders in the same area pick the same fast relays, so they share one.

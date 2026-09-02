# Consumer ProGuard/R8 rules for :ui:callui
#
# No keep rules are needed. This module is stateless Compose UI: no first-party
# reflection, no serialization plugin, no dynamic class loading.
#
# It renders a real org.webrtc VideoTrack (the one sanctioned third-party leak, ADR-025),
# but the keep rules that WebRTC's JNI needs live in :core:calling's consumer rules and
# arrive here transitively — this module `api()`s :core:calling. Do not duplicate them.

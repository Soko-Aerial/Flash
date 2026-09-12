# Consumer ProGuard/R8 rules for :core:ptt
#
# The module uses no reflection, serialization plugin, or native method lookup. Android's
# AudioRecord/AudioTrack classes are referenced directly, so no first-party keep rules are needed.

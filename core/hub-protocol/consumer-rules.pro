# Consumer ProGuard/R8 rules for :core:hub-protocol
#
# The certificate, relay-frame and REST types are @Serializable (kotlinx.serialization compiler plugin generates a
# static serializer for each; there is no reflective lookup of first-party types). kotlinx-serialization-json ships
# its own consumer rules for the runtime. No first-party keep rule is needed. If a future change makes a hub type
# polymorphic through reflection or a name-based lookup, add a matching -keep here.

# Consumer ProGuard/R8 rules for :core:enterprise
#
# No keep rules are needed. The hub client has no first-party reflection and no serialization plugin of its own
# (the @Serializable wire types live in :core:hub-protocol, which ships its own consumer rules).
# Transitive libraries that require keep rules ship their own consumer rules.

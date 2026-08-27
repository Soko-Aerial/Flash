# Consumer ProGuard/R8 rules for :core:common
#
# No keep rules are needed. This module exposes plain Kotlin/Java types with no
# first-party reflection, no @Serializable/serialization plugin, and no dynamic
# class loading. Any transitive libraries that require keep rules ship their own
# consumer rules. If a future change introduces reflective access to a
# first-party type, add a matching -keep here.

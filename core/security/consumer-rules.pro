# Consumer ProGuard/R8 rules for :core:security
#
# No keep rules are needed. This module has no first-party reflection, no
# serialization plugin, and no dynamic class loading. Transitive libraries that
# require keep rules ship their own consumer rules. If a future change introduces
# reflective access to a first-party type, add a matching -keep here.

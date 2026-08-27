# Consumer ProGuard/R8 rules for :core:messaging
#
# EXPERIMENTAL module — not promised in the v1 supported set (still DAO-coupled;
# pulled transitively by :core:engine). No keep rules are needed: no first-party
# reflection, no serialization plugin, no dynamic class loading. Room (transitive)
# ships its own consumer rules for its generated code. If a future change
# introduces reflective access to a first-party type, add a matching -keep here.

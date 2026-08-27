# Consumer ProGuard/R8 rules for :core:engine
#
# :core:engine is the batteries-included umbrella (Flash.create). No FIRST-PARTY
# keep rules are needed: no first-party reflection, no serialization plugin, and no
# dynamic class loading of its own types.
#
# It bundles Room + SQLCipher transitively (encrypted DB). Those libraries ship
# their OWN consumer ProGuard rules, so consumers do not need to add keep rules for
# them here. If a future change adds reflective access to a first-party type, add a
# matching -keep here.

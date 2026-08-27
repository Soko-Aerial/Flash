# Consumer ProGuard/R8 rules for :core:persistence
#
# No FIRST-PARTY keep rules are needed. This module has no first-party reflection,
# no serialization plugin, and no dynamic class loading of its own types.
#
# It does bundle Room and SQLCipher (net.zetetic), which rely on generated code and
# JNI. Those libraries ship their OWN consumer ProGuard rules (room-runtime and the
# sqlcipher AAR), so consumers do not need to add keep rules for them here. Room's
# generated *_Impl DAOs and @Entity/@Dao types are preserved by Room's packaged
# rules. If a future change adds reflective access to a first-party type, add a
# matching -keep here.

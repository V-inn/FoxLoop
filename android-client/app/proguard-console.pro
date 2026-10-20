# R8 rules for FoxLoop Console's release build. The display needs none.
#
# BouncyCastle's JCA provider registers every algorithm by class *name* and
# instantiates it reflectively, so R8 sees nothing referencing those classes and
# would strip or rename them -- the failure is a NoSuchAlgorithmException at the
# first key exchange, on a release build only. Kept whole: the provider's own
# tables are the only list of what it may load.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }

# sshj itself is wired with plain `new`, but keeps its algorithm names in the
# classes, and an obfuscated stack trace from inside a key exchange is not one
# anyone can act on. Kept for readable failures, not for correctness.
-keepnames class net.schmizz.sshj.** { *; }
-keepnames class com.hierynomus.sshj.** { *; }

# Referenced by code paths the console never takes (GSSAPI authentication,
# JNDI lookups in BouncyCastle's LDAP store, logging bindings). Android has none
# of these; the classes are never loaded.
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.login.**
-dontwarn org.slf4j.impl.**

# JSch (SSH) loads cipher/kex/mac/signature classes by reflection from their string names.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# JSch references optional JZlib + Bouncy Castle / ed25519 providers it may not ship; ignore if absent.
-dontwarn com.jcraft.jzlib.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.**
# commons-net FTP client.
-dontwarn org.apache.commons.net.**

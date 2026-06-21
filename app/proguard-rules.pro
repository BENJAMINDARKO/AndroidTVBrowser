# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/mmm/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any custom rules here that might be needed for GeckoView or other libraries.

# Ignore missing java.beans classes (not available on Android)
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

# GeckoView recommended rules
-keep class org.mozilla.geckoview.** { *; }

########################################
# Android 標準・Media関連
########################################
-dontwarn android.media.LoudnessCodecController$OnLoudnessCodecUpdateListener
-dontwarn android.media.LoudnessCodecController

########################################
# Google Play Services (AdMob, Maps)
########################################
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.maps.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.internal.** { *; }

########################################
# Firebase（必要に応じて）
########################################
-keep class com.google.firebase.** { *; }

########################################
# JSON / XML / その他
########################################
-keep class org.json.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

########################################
# AndroidX ライブラリ
########################################
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.recyclerview.** { *; }
-keep class androidx.cardview.** { *; }

########################################
# リフレクションでアクセスされる可能性のあるクラス（例：View Binding）
########################################
-keep class * extends android.app.Activity
-keepclassmembers class * {
    public <init>(android.content.Context);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

########################################
# アプリ固有のパッケージ
########################################
-keep class com.ykun.orbisalert.** { *; }

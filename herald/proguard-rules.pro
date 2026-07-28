# GeckoView and Mozilla Android Components reach into their own classes from
# native code and from JavaScript, so their entry points cannot be renamed.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class mozilla.appservices.** { *; }
-keep class mozilla.components.service.glean.** { *; }

# JNA, used by the application-services (places/logins) bindings.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# kotlinx.serialization looks up generated serializers reflectively.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-keepclassmembers class app.drawbridge.** {
    *** Companion;
}
-keepclasseswithmembers class app.drawbridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Views inflated from XML by name.
-keep class mozilla.components.**.view.** { *; }
-keep class mozilla.components.concept.engine.EngineView { *; }

# kotlinx.serialization generates synthetic serializer classes that are looked up
# reflectively by name; keep them for anything in the policy model package.
-keepclassmembers class app.drawbridge.policy.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.drawbridge.policy.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

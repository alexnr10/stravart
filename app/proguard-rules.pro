# kotlinx.serialization : on n'utilise que l'API JsonElement, mais le moteur
# conserve des références au sérialiseur intégré.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# osmdroid charge certaines classes par réflexion (sources de tuiles, cache).
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.beakon.companion.** {
    *** Companion;
}
-keepclasseswithmembers class com.beakon.companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}

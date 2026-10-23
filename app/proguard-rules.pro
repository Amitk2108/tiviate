# Tiviate R8 rules. Libraries (Media3, Room, OkHttp, Coil, Compose) ship their own consumer rules.
# XmlPullParser is provided by the Android platform at runtime.
-dontwarn org.xmlpull.v1.**
-dontwarn org.kxml2.**

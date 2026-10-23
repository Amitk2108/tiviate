plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.okio)
    implementation(libs.moshi)
    implementation(libs.xz)
    compileOnly(libs.xmlpull)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
}

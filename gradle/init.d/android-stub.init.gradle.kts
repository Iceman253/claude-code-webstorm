// Sets a local stub URL for the jb.gg Android Studio releases XML
// (DNS-blocked on this machine). Must use allprojects/gradle lifecycle hook
// so rootProject is available.
gradle.settingsEvaluated {
    val stubUrl = settings.rootDir.resolve("android-studio-releases.xml").toURI().toString()
    System.setProperty("org.jetbrains.intellij.platform.androidStudioReleasesUrl", stubUrl)
}

# The hidden-WebView engine fallback calls these from JavaScript; keep them when apps shrink.
-keepclassmembers class space.nimbusgo.sigma.engine.WebViewRuntime$Host {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class space.nimbusgo.sigma.** { public *; }

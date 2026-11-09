package com.robat2.mobile;

import java.util.Locale;

/** Small, offline starter reference. Full vendor documentation can be imported as Markdown/ZIP. */
public final class ProgrammingDocs {
    private ProgrammingDocs() {}

    public static String search(String query, int maxChars) {
        String q=query==null?"":query.toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();
        add(out,q,maxChars,"android","android activity lifecycle gradle manifest permission intent","Android: Activity lifecycle = onCreate → onStart → onResume; leaving = onPause → onStop; possible destruction = onDestroy. Register UI listeners in onCreate; release camera/audio/sensors when appropriate. Manifest declares components and permissions. Use Activity Result APIs for document/photo pickers.");
        add(out,q,maxChars,"java","java class method exception collection thread","Java: prefer small classes, explicit types, try-with-resources for streams, validate nulls and bounds. Use ExecutorService for I/O and long work; update Android views only on the main thread. Catch specific exceptions; do not silently convert failures into success.");
        add(out,q,maxChars,"kotlin","kotlin coroutine null safety data class","Kotlin: use data class for value models, val by default, nullable types explicitly, sealed class for result states. Use coroutines with Dispatchers.IO for blocking I/O and Dispatchers.Main for UI; cancel jobs when the screen is destroyed.");
        add(out,q,maxChars,"gradle","gradle build.gradle dependency plugin version","Gradle: declare plugins and dependencies in the module build.gradle; keep compileSdk/targetSdk intentional. Use implementation for runtime dependencies and testImplementation for tests. Pin versions; avoid dynamic '+'. Verify with ./gradlew test and ./gradlew assembleDebug.");
        add(out,q,maxChars,"python","python venv pip pytest","Python: isolate dependencies in venv; pin requirements; use pathlib for paths, context managers for files, type hints for public APIs, pytest for tests. Never execute untrusted input with eval/exec or shell=True.");
        add(out,q,maxChars,"javascript","javascript typescript async promise fetch","JavaScript/TypeScript: prefer const, strict equality, explicit async/await error handling, and input validation. Avoid blocking the UI thread. Use TypeScript types for public interfaces and tests for edge cases.");
        add(out,q,maxChars,"cpp","c++ cpp memory raii cmake","C++: use RAII and standard containers; avoid raw owning pointers; check return codes and bounds. Build with CMake; keep JNI boundaries small and translate exceptions/errors safely. Never hold a global mutex during long inference if stop requests need that lock.");
        add(out,q,maxChars,"git","git github pull request branch commit","Git: work on a feature branch; inspect git diff; commit focused changes; run tests before opening a PR. Never commit tokens, signing keys, wallet seeds, local.properties, or build outputs.");
        add(out,q,maxChars,"api","rest api http json authentication","REST API: define method, path, request/response schema, status codes, pagination, timeouts and retry policy. Retry only transient failures with bounded exponential backoff; never interpret API errors as empty/zero data.");
        add(out,q,maxChars,"security","security encryption secret token permission","Security: least privilege; validate paths against canonical workspace root; limit file sizes; use HTTPS; keep secrets out of logs and source control. Treat imported project files and model output as untrusted; never run generated shell commands automatically.");
        add(out,q,maxChars,"testing","test junit unit test integration","Testing: cover happy path, invalid input, boundary sizes, network timeout, cancellation and persistence. Separate unit tests from integration/device tests; report exactly which tests ran and their result.");
        if(out.length()==0) out.append("راهنمای آفلاین: نام زبان، فریم‌ورک یا خطای برنامه را دقیق بنویس تا بخش مرتبط از مستندات محلی انتخاب شود. مستندات کامل‌تر را می‌توانی به‌صورت ZIP یا Markdown وارد کتابخانه کنی.");
        return out.toString();
    }

    private static void add(StringBuilder out,String q,int max,String title,String keys,String text) {
        if(out.length()>=max) return;
        boolean match=false;
        for(String token:keys.split(" ")) if(token.length()>2 && q.contains(token)) { match=true; break; }
        if(match) {
            String block="\n\n[OFFLINE DOCS: "+title+"]\n"+text;
            int take=Math.min(block.length(),max-out.length());
            if(take>0) out.append(block,0,take);
        }
    }
}

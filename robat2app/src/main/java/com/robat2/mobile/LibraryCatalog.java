package com.robat2.mobile;

import java.util.Locale;

/** Offline index of common programming libraries; dependencies are not bundled automatically. */
public final class LibraryCatalog {
    private LibraryCatalog() {}

    public static String search(String query,int maxChars) {
        String q=query==null?"":query.toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();
        add(out,q,maxChars,"android","android app retrofit okhttp room workmanager compose jetpack","Android libraries: AndroidX Core/Activity/Lifecycle for app foundations; Room for SQLite persistence; WorkManager for deferrable background jobs; Retrofit + OkHttp for REST; Moshi or Gson for JSON; Kotlin Coroutines for async work; Jetpack Compose for declarative UI; JUnit and Espresso for tests. Add only what the task needs.");
        add(out,q,maxChars,"java","java gson jackson junit mockito slf4j","Java: Jackson or Gson for JSON; JUnit 5 for tests; Mockito for test doubles; SLF4J for logging; Apache Commons only when a focused utility is needed.");
        add(out,q,maxChars,"python","python fastapi django flask requests httpx sqlalchemy pytest pandas","Python: FastAPI for APIs; Django for full web apps; httpx for HTTP; SQLAlchemy for database access; pytest for tests; pandas for tabular analysis. Use a virtual environment and pinned requirements.");
        add(out,q,maxChars,"javascript","javascript typescript react vue express zod axios vitest playwright","JavaScript/TypeScript: React or Vue for UI; Express for Node APIs; Zod for schema validation; Axios or built-in fetch for HTTP; Vitest for unit tests; Playwright for browser tests.");
        add(out,q,maxChars,"cpp","c++ cpp cmake googletest catch2 fmt spdlog","C/C++: CMake for builds; GoogleTest or Catch2 for tests; fmt for formatting; spdlog for logging. Prefer standard library features before adding dependencies.");
        add(out,q,maxChars,"security","crypto encryption security library","Security libraries must be maintained and platform-appropriate. Use Android Keystore for device-held keys; do not invent cryptography or copy unreviewed crypto code. Never log secrets.");
        if(out.length()==0) out.append("کتابخانه‌ها: زبان و پلتفرم پروژه را مشخص کن تا وابستگی‌های مناسب، نسخه سازگار و روش نصب پیشنهاد شود. وابستگی‌ها بدون تأیید و بررسی مجوز به پروژه اضافه نمی‌شوند.");
        return out.toString();
    }
    private static void add(StringBuilder out,String q,int max,String title,String keys,String body) {
        if(out.length()>=max) return;
        boolean match=false;
        for(String token:keys.split(" ")) if(token.length()>2 && q.contains(token)) { match=true; break; }
        if(match) {
            String block="\n\n[LIBRARY CATALOG: "+title+"]\n"+body;
            int n=Math.min(block.length(),max-out.length());
            if(n>0) out.append(block,0,n);
        }
    }
}

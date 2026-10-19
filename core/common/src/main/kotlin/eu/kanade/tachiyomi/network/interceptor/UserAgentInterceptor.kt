package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val userAgent = originalRequest.header("User-Agent").takeUnless { it.isNullOrBlank() }
            ?: defaultUserAgentProvider()

        val builder = originalRequest.newBuilder()

        if (originalRequest.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }

        // Add standard Chromium Client Hints if not present (matches WebView on Android)
        if (originalRequest.header("Sec-CH-UA") == null) {
            val match = CHROME_VERSION_REGEX.find(userAgent)
            val major = match?.groupValues?.getOrNull(1) ?: "130"
            val full = major + (match?.groupValues?.getOrNull(2)?.ifEmpty { ".0.0.0" } ?: ".0.6723.102")

            builder.header("Sec-CH-UA", "\"Chromium\";v=\"$major\", \"Not?A_Brand\";v=\"24\", \"Google Chrome\";v=\"$major\"")
            builder.header("Sec-CH-UA-Mobile", "?1")
            builder.header("Sec-CH-UA-Platform", "\"Android\"")
            builder.header("Sec-CH-UA-Platform-Version", "\"10.0.0\"")
            builder.header("Sec-CH-UA-Arch", "\"\"")
            builder.header("Sec-CH-UA-Bitness", "\"64\"")
            builder.header("Sec-CH-UA-Model", "\"K\"")
            builder.header("Sec-CH-UA-Full-Version", "\"$full\"")
            builder.header("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"$full\", \"Not?A_Brand\";v=\"24.0.0.0\", \"Google Chrome\";v=\"$full\"")
        }

        return chain.proceed(builder.build())
    }

    companion object {
        private val CHROME_VERSION_REGEX = """Chrome/(\d+)(\.[\d.]+)?""".toRegex()
    }
}


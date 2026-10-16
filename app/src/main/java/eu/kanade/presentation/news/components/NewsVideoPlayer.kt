package eu.kanade.presentation.news.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.data.news.model.extractYouTubeVideoId

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NewsVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
    title: String = "Official Video Trailer",
) {
    val context = LocalContext.current
    val videoId = remember(videoUrl) { extractYouTubeVideoId(videoUrl) }
    val effectiveEmbedUrl = remember(videoUrl, videoId) {
        if (!videoId.isNullOrBlank()) {
            "https://www.youtube-nocookie.com/embed/$videoId?enablejsapi=1&origin=https://yomite.vercel.app&rel=0&modestbranding=1&playsinline=1"
        } else if (videoUrl.contains("youtube.com/embed/")) {
            videoUrl
        } else {
            videoUrl
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Embedded Video Player (16:9 ratio)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                DisposableEffect(effectiveEmbedUrl) {
                    onDispose {
                        try {
                            webViewRef?.stopLoading()
                            webViewRef?.onPause()
                            webViewRef?.destroy()
                        } catch (_: Exception) {}
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            }
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val targetUri = request?.url?.toString() ?: return false
                                    if (targetUri.contains("youtube.com/watch") || targetUri.contains("youtu.be/")) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUri))
                                            ctx.startActivity(intent)
                                            return true
                                        } catch (_: Exception) {}
                                    }
                                    return false
                                }
                            }
                            setBackgroundColor(android.graphics.Color.BLACK)

                            val html = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                    <style>
                                        * { margin: 0; padding: 0; box-sizing: border-box; }
                                        html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                                        .video-box { position: relative; width: 100%; height: 100%; }
                                        iframe { width: 100%; height: 100%; border: 0; }
                                    </style>
                                </head>
                                <body>
                                    <div class="video-box">
                                        <iframe 
                                            src="$effectiveEmbedUrl" 
                                            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" 
                                            allowfullscreen>
                                        </iframe>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()

                            loadDataWithBaseURL("https://yomite.vercel.app", html, "text/html", "UTF-8", null)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        val externalUrl = if (!videoId.isNullOrBlank()) {
                            "https://www.youtube.com/watch?v=$videoId"
                        } else {
                            videoUrl
                        }
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open in YouTube",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

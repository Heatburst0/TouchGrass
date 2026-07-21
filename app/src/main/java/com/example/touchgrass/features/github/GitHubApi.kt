package com.example.touchgrass.features.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class GitHubException(message: String) : Exception(message)

@Singleton
class GitHubApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * True if the repo has ≥1 commit in [since, until].
     * @param author GitHub login to filter by, or null to count any commit.
     * @throws GitHubException on a definitive API error (never on "no commits").
     * @throws IOException on network failure — callers must NOT treat this as a miss.
     */
    suspend fun hasCommit(
        owner: String,
        repo: String,
        author: String?,
        since: Instant,
        until: Instant,
        token: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://api.github.com/repos/")
            append(owner.trim()).append('/').append(repo.trim()).append("/commits")
            append("?per_page=1")
            append("&since=").append(since.toString())
            append("&until=").append(until.toString())
            if (!author.isNullOrBlank()) append("&author=").append(author.trim())
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> JSONArray(body).length() > 0
                // Empty repository — the API 409s; treat as simply "no commits".
                response.code == 409 -> false
                response.code == 404 -> throw GitHubException(
                    "Repo not found: $owner/$repo. Check the name (private repos need a token)."
                )
                response.code == 401 -> throw GitHubException("GitHub token is invalid.")
                response.code == 403 -> throw GitHubException(
                    if (body.contains("rate limit", true))
                        "GitHub rate limit hit. Add a token, or try again later."
                    else "GitHub access denied (check the token's repo scope)."
                )
                else -> throw GitHubException("GitHub error (HTTP ${response.code}).")
            }
        }
    }
}

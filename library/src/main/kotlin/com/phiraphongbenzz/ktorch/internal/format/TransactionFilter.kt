package com.phiraphongbenzz.ktorch.internal.format

import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction

internal enum class StatusFilter {
    ALL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, FAILED;

    fun matches(t: HttpTransaction): Boolean {
        val code = t.responseCode
        return when (this) {
            ALL -> true
            SUCCESS -> code in 200..299
            REDIRECT -> code in 300..399
            CLIENT_ERROR -> code in 400..499
            SERVER_ERROR -> code in 500..599
            FAILED -> code == null && t.error != null
        }
    }
}

internal fun List<HttpTransaction>.filterBy(query: String, status: StatusFilter): List<HttpTransaction> {
    val needle = query.trim()
    return filter { t ->
        status.matches(t) && (needle.isEmpty() || t.url.contains(needle, ignoreCase = true))
    }
}

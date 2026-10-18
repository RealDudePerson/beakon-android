package com.beakon.companion.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Minimal in-memory cookie jar. The Beakon `session` cookie authenticates every
 * endpoint except `/api/recordlocation`, which uses the `username`/`secret`
 * headers instead.
 */
class SimpleCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        for (cookie in cookies) {
            list.removeAll { it.name == cookie.name }
            list.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.removeAll { it.expiresAt < now }
        return list.filter { it.matches(url) }
    }

    @Synchronized
    fun cookie(name: String): Cookie? {
        val now = System.currentTimeMillis()
        return store.values.flatten().firstOrNull { it.name == name && it.expiresAt > now }
    }

    /** Seeds a cookie without a server round-trip (used to restore a persisted session). */
    @Synchronized
    fun seed(host: String, name: String, value: String) {
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain(host)
            .path("/")
            .build()
        val list = store.getOrPut(host) { mutableListOf() }
        list.removeAll { it.name == name }
        list.add(cookie)
    }

    @Synchronized
    fun clear() {
        store.clear()
    }
}

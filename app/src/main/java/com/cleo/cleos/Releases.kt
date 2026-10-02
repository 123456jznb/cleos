package com.cleo.cleos

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where every release goes: one 蓝奏云 folder, so the link stays the same from one version to the
 * next. 蓝奏云 retires its domains now and then: lanzouc.com stopped resolving in 2026-10, and every
 * copy of the app with only that address in it lost its way to new versions. The folder's path
 * works on all of its domains, so there are several here, tried in order.
 */
object Releases {
    val HOSTS = listOf("wwbnf.lanzouw.com", "wwbnf.lanzoui.com", "wwbnf.lanzoux.com", "wwbnf.lanzoum.com")
    const val PATH = "/b01gicbubg"

    /** The folder's password: the page asks for it once. */
    const val CODE = "5y4u"

    fun url(host: String) = "https://$host$PATH"

    /** The folder on the first host that [resolves]; on the first of all when none does (no network: the browser says so). */
    fun pick(resolves: (String) -> Boolean): String = url(HOSTS.firstOrNull(resolves) ?: HOSTS.first())

    /** The folder on a domain that still exists, looked up when the button is pressed. */
    suspend fun reachableUrl(): String = withContext(Dispatchers.IO) {
        pick { host -> runCatching { InetAddress.getByName(host) }.isSuccess }
    }
}

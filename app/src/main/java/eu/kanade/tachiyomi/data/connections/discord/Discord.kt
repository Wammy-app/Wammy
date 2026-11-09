package eu.kanade.tachiyomi.data.connections.discord

import android.app.Application
import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Discord(id: Long) : ConnectionsService(id) {

    override fun nameStrRes() = MR.strings.connections_discord

    override fun getLogo() = R.drawable.ic_discord_24dp

    @Suppress("MagicNumber")
    override fun getLogoColor() = Color.rgb(88, 101, 242)

    override fun logout() {
        val oldToken = getToken()
        super.logout()
        connectionsPreferences.connectionsToken(this).delete()
        connectionsPreferences.setConnectionsCredentials(this, "", "")
        connectionsPreferences.enableDiscordRPC().set(false)
        connectionsPreferences.discordAccounts().delete()
        val context = try { Injekt.get<Application>() } catch (_: Exception) { null }
        if (context != null) {
            DiscordRPCService.stop(context, 0L)
        }
        if (oldToken.isNotBlank()) {
            DiscordRPCService.discordScope.launchIO {
                DiscordRPCService.clearDiscordPresence(oldToken)
            }
        }
    }

    override suspend fun login(username: String, password: String) {
        // Token based login via WebView
    }

    override val isLogged: Boolean
        get() {
            val token = getToken()
            val accounts = getAccounts()
            if (token.isNotBlank() && accounts.isEmpty()) {
                logout()
                return false
            }
            return token.isNotBlank() && accounts.isNotEmpty()
        }

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            connectionsPreferences.connectionsToken(this).changes(),
            connectionsPreferences.discordAccounts().changes(),
        ) { token, accountsJson ->
            token.isNotBlank() && accountsJson.isNotBlank() && accountsJson != "[]"
        }
    }

    override fun getToken(): String {
        return connectionsPreferences.connectionsToken(this).get()
    }

    private val json = Injekt.get<Json>()

    fun getAccounts(): List<DiscordAccount> {
        val accountsJson = connectionsPreferences.discordAccounts().get()
        return try {
            if (accountsJson.isNotBlank()) {
                json.decodeFromString<List<DiscordAccount>>(accountsJson)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse Discord accounts: ${e.message}" }
            emptyList()
        }
    }

    fun addAccount(account: DiscordAccount) {
        val accounts = getAccounts().toMutableList()

        if (account.isActive) {
            accounts.replaceAll { it.copy(isActive = false) }
            connectionsPreferences.connectionsToken(this).set(account.token)
        }

        val index = accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            accounts[index] = account
        } else {
            accounts.add(account)
        }

        saveAccounts(accounts)
    }

    fun removeAccount(accountId: String) {
        val accounts = getAccounts().toMutableList()
        val removed = accounts.find { it.id == accountId }
        val wasActive = removed?.isActive == true || removed?.token == getToken()
        val removedToken = removed?.token ?: ""
        accounts.removeAll { it.id == accountId }
        saveAccounts(accounts)

        val context = try { Injekt.get<Application>() } catch (_: Exception) { null }

        if (accounts.isEmpty()) {
            logout()
            if (removedToken.isNotBlank()) {
                DiscordRPCService.discordScope.launchIO {
                    DiscordRPCService.clearDiscordPresence(removedToken)
                }
            }
        } else if (wasActive) {
            val next = accounts.first().copy(isActive = true)
            accounts[0] = next
            saveAccounts(accounts)
            connectionsPreferences.connectionsToken(this).set(next.token)
            if (removedToken.isNotBlank() && removedToken != next.token) {
                DiscordRPCService.discordScope.launchIO {
                    DiscordRPCService.clearDiscordPresence(removedToken)
                }
            }
            if (context != null) {
                restartRichPresence(context)
            }
        }
    }

    fun setActiveAccount(accountId: String) {
        val accounts = getAccounts().toMutableList()
        accounts.replaceAll { it.copy(isActive = it.id == accountId) }
        saveAccounts(accounts)
        accounts.find { it.id == accountId }?.let { account ->
            connectionsPreferences.connectionsToken(this).set(account.token)
        }
    }

    fun restartRichPresence(context: Context) {
        DiscordRPCService.restart(context)
    }

    private fun saveAccounts(accounts: List<DiscordAccount>) {
        try {
            val accountsJson = json.encodeToString(accounts)
            connectionsPreferences.discordAccounts().set(accountsJson)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to save Discord accounts: ${e.message}" }
        }
    }
}

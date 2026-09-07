package com.glut.schedule.partner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class StoredPartnerInvite(
    val code: String,
    val revokeToken: String,
    val expiresAt: String
)

interface PartnerScheduleStorage {
    val profiles: StateFlow<List<ImportedPartnerProfile>>
    val activeInvite: StateFlow<StoredPartnerInvite?>
    val myColor: StateFlow<PartnerIdentityColor>
    fun saveProfiles(profiles: List<ImportedPartnerProfile>)
    fun saveActiveInvite(invite: PartnerInvite)
    fun clearActiveInvite()
    fun setMyColor(color: PartnerIdentityColor)
}

class PartnerScheduleStore(context: Context) : PartnerScheduleStorage {
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "partner_schedule_secure_data",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _profiles = MutableStateFlow(readProfiles())
    override val profiles: StateFlow<List<ImportedPartnerProfile>> = _profiles.asStateFlow()

    private val _activeInvite = MutableStateFlow(readInvite())
    override val activeInvite: StateFlow<StoredPartnerInvite?> = _activeInvite.asStateFlow()

    private val _myColor = MutableStateFlow(
        runCatching {
            PartnerIdentityColor.fromStorage(
                securePrefs.getString(KEY_MY_COLOR, PartnerIdentityColor.BLUE.storageValue).orEmpty()
            )
        }.getOrDefault(PartnerIdentityColor.BLUE)
    )
    override val myColor: StateFlow<PartnerIdentityColor> = _myColor.asStateFlow()

    override fun saveProfiles(profiles: List<ImportedPartnerProfile>) {
        require(profiles.size <= MAX_PROFILE_COUNT) { "最多只能保存两份课表" }
        securePrefs.edit()
            .putString(KEY_PROFILES, encodeProfiles(profiles))
            .commit()
        _profiles.value = profiles
    }

    override fun saveActiveInvite(invite: PartnerInvite) {
        securePrefs.edit()
            .putString(KEY_INVITE_CODE, invite.code)
            .putString(KEY_REVOKE_TOKEN, invite.revokeToken)
            .putString(KEY_EXPIRES_AT, invite.expiresAt)
            .commit()
        _activeInvite.value = StoredPartnerInvite(invite.code, invite.revokeToken, invite.expiresAt)
    }

    override fun clearActiveInvite() {
        securePrefs.edit()
            .remove(KEY_INVITE_CODE)
            .remove(KEY_REVOKE_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .commit()
        _activeInvite.value = null
    }

    override fun setMyColor(color: PartnerIdentityColor) {
        securePrefs.edit().putString(KEY_MY_COLOR, color.storageValue).apply()
        _myColor.value = color
    }

    private fun readProfiles(): List<ImportedPartnerProfile> {
        val profiles = securePrefs.getString(KEY_PROFILES, null)?.let(::decodeProfiles)
        if (profiles != null) return profiles
        val raw = securePrefs.getString(KEY_PARTNER_SNAPSHOT, null) ?: return emptyList()
        val snapshot = runCatching { PartnerScheduleSnapshotCodec.decode(raw) }.getOrNull() ?: return emptyList()
        // v1 单槽位数据保留为可编辑的首个档案，避免升级后丢失 TA 课表。
        return listOf(
            ImportedPartnerProfile("profile-1", partnerProfileDefaultName(0), snapshot, snapshot.identityColor)
        )
    }

    private fun encodeProfiles(profiles: List<ImportedPartnerProfile>): String = JSONArray(
        profiles.map { profile ->
            JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("displayColor", profile.displayColor.storageValue)
                .put("snapshot", JSONObject(PartnerScheduleSnapshotCodec.encode(profile.snapshot)))
        }
    ).toString()

    private fun decodeProfiles(raw: String): List<ImportedPartnerProfile>? = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= MAX_PROFILE_COUNT)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ImportedPartnerProfile(
                id = item.getString("id").trim().also { require(it.isNotEmpty()) },
                name = item.getString("name").trim().take(20).ifEmpty { partnerProfileDefaultName(index) },
                snapshot = PartnerScheduleSnapshotCodec.decode(item.getJSONObject("snapshot").toString()),
                displayColor = PartnerIdentityColor.fromStorage(item.getString("displayColor"))
            )
        }.also { profiles -> require(profiles.map { it.id }.distinct().size == profiles.size) }
    }.getOrNull()

    private fun readInvite(): StoredPartnerInvite? {
        val code = securePrefs.getString(KEY_INVITE_CODE, "").orEmpty()
        val revokeToken = securePrefs.getString(KEY_REVOKE_TOKEN, "").orEmpty()
        val expiresAt = securePrefs.getString(KEY_EXPIRES_AT, "").orEmpty()
        return if (code.isBlank() || revokeToken.isBlank() || expiresAt.isBlank()) {
            null
        } else {
            StoredPartnerInvite(code, revokeToken, expiresAt)
        }
    }

    private companion object {
        const val KEY_PARTNER_SNAPSHOT = "partner_snapshot_v1"
        const val KEY_PROFILES = "partner_profiles_v2"
        const val KEY_INVITE_CODE = "active_invite_code"
        const val KEY_REVOKE_TOKEN = "active_invite_revoke_token"
        const val KEY_EXPIRES_AT = "active_invite_expires_at"
        const val KEY_MY_COLOR = "my_identity_color"
        const val MAX_PROFILE_COUNT = 2
    }
}

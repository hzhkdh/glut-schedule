package com.glut.schedule.partner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredPartnerInvite(
    val code: String,
    val revokeToken: String,
    val expiresAt: String
)

interface PartnerScheduleStorage {
    val partnerSnapshot: StateFlow<PartnerScheduleSnapshot?>
    val activeInvite: StateFlow<StoredPartnerInvite?>
    val myColor: StateFlow<PartnerIdentityColor>
    fun savePartnerSnapshot(snapshot: PartnerScheduleSnapshot)
    fun clearPartnerSnapshot()
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

    private val _partnerSnapshot = MutableStateFlow(readSnapshot())
    override val partnerSnapshot: StateFlow<PartnerScheduleSnapshot?> = _partnerSnapshot.asStateFlow()

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

    override fun savePartnerSnapshot(snapshot: PartnerScheduleSnapshot) {
        securePrefs.edit()
            .putString(KEY_PARTNER_SNAPSHOT, PartnerScheduleSnapshotCodec.encode(snapshot))
            .commit()
        _partnerSnapshot.value = snapshot
    }

    override fun clearPartnerSnapshot() {
        securePrefs.edit().remove(KEY_PARTNER_SNAPSHOT).commit()
        _partnerSnapshot.value = null
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

    private fun readSnapshot(): PartnerScheduleSnapshot? {
        val raw = securePrefs.getString(KEY_PARTNER_SNAPSHOT, null) ?: return null
        return runCatching { PartnerScheduleSnapshotCodec.decode(raw) }.getOrNull()
    }

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
        const val KEY_INVITE_CODE = "active_invite_code"
        const val KEY_REVOKE_TOKEN = "active_invite_revoke_token"
        const val KEY_EXPIRES_AT = "active_invite_expires_at"
        const val KEY_MY_COLOR = "my_identity_color"
    }
}

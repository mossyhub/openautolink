package com.openautolink.app.data

/** Durable intent only. Never stores account authority, calls, or session owners. */
data class CarPlayCallPreferences(
    val handler: Handler = Handler.CARPLAY,
    val extraRingtone: Boolean = false,
) {
    enum class Handler { CARPLAY, CAR_BLUETOOTH }
    fun encode(): String = "1|${handler.name}|$extraRingtone"
    companion object {
        /** Missing records are fresh defaults. Invalid records fail closed to car Bluetooth. */
        fun decode(record: String?): CarPlayCallPreferences {
            if (record == null) return CarPlayCallPreferences()
            val fields = record.split('|')
            val handler = fields.getOrNull(1)?.let { value -> Handler.entries.find { it.name == value } }
            val ringtone = fields.getOrNull(2)?.toBooleanStrictOrNull()
            return if (fields.size == 3 && fields[0] == "1" && handler != null && ringtone != null)
                CarPlayCallPreferences(handler, ringtone)
            else CarPlayCallPreferences(Handler.CAR_BLUETOOTH)
        }
    }
}

#pragma once
// aa_error_codes.hpp — Phone-side Android Auto error code lookup tables.
//
// Reverse-engineered from the AA phone APK (ygs.java, ygt.java).
// These codes appear in phone logcat as "Communication Error N" or
// "CriticalError(code, detail)". The bridge never receives these directly —
// it only sees aasdk channel errors (TCP EOF, SSL error, etc.) — but
// mapping the phone logcat codes helps diagnose WHY the phone dropped.
//
// Usage:
//   BLOG << "phone error: " << aa_error_name(6) << std::endl;
//   BLOG << "phone detail: " << aa_error_detail_name(41) << std::endl;

#include <cstdint>

// Primary error codes (ygs.java — CriticalError.code)
// Shown on phone as "Communication Error N"
inline const char* aa_error_name(int code) {
    switch (code) {
    case 0:    return "UNKNOWN_CODE";
    case 1:    return "PROTOCOL_INCOMPATIBLE_VERSION";
    case 2:    return "PROTOCOL_WRONG_CONFIGURATION";
    case 3:    return "PROTOCOL_IO_ERROR";
    case 4:    return "PROTOCOL_BYEBYE_REQUESTED_BY_CAR";
    case 5:    return "PROTOCOL_BYEBYE_REQUESTED_BY_USER";
    case 6:    return "PROTOCOL_WRONG_MESSAGE";
    case 7:    return "PROTOCOL_AUTH_FAILED";
    case 8:    return "PROTOCOL_AUTH_FAILED_BY_CAR";
    case 9:    return "TIMEOUT";
    case 10:   return "NO_LAUNCHER";
    case 11:   return "COMPOSITION";
    case 12:   return "CAR_NOT_RESPONDING";
    case 13:   return "PROTOCOL_AUTH_FAILED_BY_CAR_CERT_NOT_YET_VALID";
    case 14:   return "PROTOCOL_AUTH_FAILED_BY_CAR_CERT_EXPIRED";
    case 15:   return "CONNECTION_ERROR";
    case 16:   return "USB_ACCESSORY_ERROR";
    case 17:   return "CAR_SERVICE_INIT_ERROR";
    case 18:   return "CAR_SERVICE_CONNECTION_ERROR";
    case 19:   return "USB_CHARGE_ONLY";
    case 20:   return "PREFLIGHT_FAILED";
    case 21:   return "SOCKET_VPN_CONNECTION_ERROR";
    case 22:   return "NO_MANAGE_USB_PERMISSION_ERROR";
    case 23:   return "FRX_ERROR";
    case 24:   return "AUDIO_ERROR";
    case 25:   return "NO_NEARBY_DEVICES_PERMISSION_ERROR";
    case 26:   return "PROJECTION_PROCESS_CRASH_LOOP";
    case 1000: return "BLUETOOTH_FAILURE";
    case 1001: return "SESSION_OBSOLETE_DUE_TO_NEW_CONNECTION";
    default:   return "?";
    }
}

// Detail codes (ygt.java — CriticalError.detail)
// Provides sub-reason for the primary error
inline const char* aa_error_detail_name(int detail) {
    switch (detail) {
    case 0:  return "UNKNOWN_DETAIL";
    case 1:  return "NO_SENSORS";
    case 2:  return "SENSORS_FAIL";
    case 3:  return "NO_ACCESSORY_PERMISSION";
    case 4:  return "NO_ACCESSORY_FD";
    case 5:  return "SOCKET_FAIL";
    case 6:  return "BAD_MIC_AUDIO_CONFIG";
    case 7:  return "BAD_AUDIO_CONFIG";
    case 8:  return "MISSING_AUDIO_CONFIG";
    case 9:  return "NAV_NO_IMAGE_OPTIONS";
    case 10: return "NAV_BAD_SIZE";
    case 11: return "NAV_BAD_COLOR";
    case 12: return "BAD_VIDEO";
    case 13: return "MISSING_VIDEO";
    case 14: return "DISPLAY_REMOVAL_TIMEOUT";
    case 15: return "NO_AUDIO_CAPTURE";
    case 16: return "MISSING_LAUNCHER";
    case 17: return "NO_VIDEO_CONFIG";
    case 18: return "BAD_CODEC_RESOLUTION";
    case 19: return "BAD_DISPLAY_RESOLUTION";
    case 20: return "BAD_FPS";
    case 21: return "NO_DENSITY";
    case 22: return "BAD_DENSITY";
    case 23: return "NO_SENSORS2";
    case 24: return "NO_AUDIO_MIC";
    case 25: return "NO_DISPLAY";
    case 26: return "NO_INPUT";
    case 27: return "COMPOSITION_RENDER_ERROR";
    case 28: return "COMPOSITION_IDLE_RENDER_ERROR";
    case 29: return "COMPOSITION_SCREENSHOT_ERROR";
    case 30: return "COMPOSITION_WINDOW_INIT_ERROR";
    case 31: return "COMPOSITION_INIT_FAIL";
    case 32: return "VENDOR_START_FAIL";
    case 33: return "VIDEO_ENCODING_INIT_FAIL";
    case 34: return "BYEBYE_BY_CAR";
    case 35: return "BYEBYE_BY_USER";
    case 36: return "UNEXPECTED_BYEBYE_RESPONSE";
    case 37: return "BYEBYE_TIMEOUT";
    case 38: return "INVALID_ACK";
    case 39: return "INVALID_ACK_CONFIG";
    case 40: return "NO_VIDEO_CONFIGS";
    case 41: return "EARLY_VIDEO_FOCUS";
    case 42: return "ERROR_STARTING_SERVICES";
    case 43: return "AUTH_FAILED";
    case 44: return "AUTH_FAILED_BY_CAR";
    case 45: return "FRAMING_ERROR";
    case 46: return "UNEXPECTED_MESSAGE";
    case 47: return "BAD_VERSION";
    case 48: return "VIDEO_ACK_TIMEOUT";
    case 49: return "AUDIO_ACK_TIMEOUT";
    case 50: return "WRITER_IO_ERROR";
    case 51: return "WRITER_UNKNOWN_EXCEPTION";
    case 52: return "READER_CLOSE";
    case 53: return "READER_INIT_FAIL";
    case 54: return "READER_IO_ERROR";
    case 55: return "AUTH_FAILED_BY_CAR_CERT_NOT_YET_VALID";
    case 56: return "AUTH_FAILED_BY_CAR_CERT_EXPIRED";
    case 57: return "PING_TIMEOUT";
    case 58: return "MULTIPLE_DISPLAY_CONFIGS";
    case 59: return "WIFI_NETWORK_UNAVAILABLE";
    case 60: return "WIFI_NETWORK_DISCONNECTED";
    case 61: return "EMPTY_USB_ACCESSORY_LIST";
    case 62: return "SPURIOUS_USB_ACCESSORY_EVENT";
    case 63: return "INVALID_ACCESSORY";
    case 64: return "CONNECTION_TRANSFER_ABORTED";
    case 65: return "DISPLAY_ID_INVALID";
    case 66: return "BAD_PRIMARY_DISPLAY";
    case 67: return "NO_ACTIVITY_LAYOUT_CONFIG";
    case 68: return "DISPLAY_CONFLICT";
    case 69: return "TOO_MANY_INPUTS";
    case 70: return "OUT_OF_MEMORY";
    case 71: return "INVALID_UI_CONFIG";
    case 72: return "NO_AUDIO_PLAYBACK_SERVICE";
    case 73: return "NO_ACTIVITY_FOUND";
    case 74: return "TOO_MANY_CRASHES";
    case 75: return "AUTH_FAILED_OBSOLETE_SSL";
    default: return "?";
    }
}

// Map aasdk ErrorCode to likely phone-side error for bridge log context.
// When the bridge sees an aasdk error, this suggests what the phone
// probably shows to the user.
inline const char* aasdk_to_phone_error_hint(int aasdk_code) {
    switch (aasdk_code) {
    case 24: // SSL_HANDSHAKE
        return "phone likely shows: Error 7 (PROTOCOL_AUTH_FAILED)";
    case 25: // SSL_WRITE
    case 26: // SSL_READ
        return "phone likely shows: Error 3 (PROTOCOL_IO_ERROR)";
    case 33: // TCP_TRANSFER
        return "phone likely shows: Error 6 (PROTOCOL_WRONG_MESSAGE) or Error 3 (PROTOCOL_IO_ERROR)";
    default:
        return nullptr;
    }
}

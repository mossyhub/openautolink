/*
 * openssl_compat.h — Compatibility shims for OpenSSL 3.x
 *
 * aasdk was written for OpenSSL 1.x and uses several deprecated/removed APIs.
 * Rather than modifying the submodule, we provide no-op shims here.
 * This header is force-included via -include in CMakeLists.txt.
 */
#pragma once

#include <openssl/opensslv.h>

#if OPENSSL_VERSION_NUMBER >= 0x30000000L

/* ENGINE_cleanup() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef ENGINE_cleanup
#define ENGINE_cleanup() ((void)0)
#endif

/* CONF_modules_unload() — still exists in 3.x but deprecated */

/* EVP_cleanup() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef EVP_cleanup
#define EVP_cleanup() ((void)0)
#endif

/* ERR_free_strings() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef ERR_free_strings
#define ERR_free_strings() ((void)0)
#endif

/* SSL_library_init() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef SSL_library_init
#define SSL_library_init() ((void)0)
#endif

/* SSL_load_error_strings() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef SSL_load_error_strings
#define SSL_load_error_strings() ((void)0)
#endif

/* OpenSSL_add_all_algorithms() removed in OpenSSL 3.0 (no-op since 1.1.0) */
#ifndef OpenSSL_add_all_algorithms
#define OpenSSL_add_all_algorithms() ((void)0)
#endif

/* CRYPTO_cleanup_all_ex_data() — still exists but deprecated */

#endif /* OPENSSL_VERSION_NUMBER >= 0x30000000L */

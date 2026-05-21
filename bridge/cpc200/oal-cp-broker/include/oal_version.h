/* oal_version.h — provides OAL_VERSION at compile time.
 *
 * The version string is injected by build.sh / CMake via -DOAL_VERSION=...
 * so there's a single source of truth in secrets/version.properties.
 */
#ifndef OAL_VERSION_H
#define OAL_VERSION_H

#ifndef OAL_VERSION
#define OAL_VERSION "0.0.0-unstamped"
#endif

#endif /* OAL_VERSION_H */

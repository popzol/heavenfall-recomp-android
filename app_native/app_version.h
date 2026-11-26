#pragma once
// The app's version, the one in heavenfall/VERSION (android/app/build.gradle's versionName; the Gradle build stops when
// the two differ): change them together
#define MH4U_APP_VERSION "1.0.0"
// The app's name, as the credits, the settings' files and the chat say it
#ifdef _WIN32
#define MH4U_APP_NAME "MH4U for PC"
#else
#define MH4U_APP_NAME "Heavenfall"
#endif
// The original project this modified version comes from, as the credits show it (ADDITIONAL-TERMS, a and b)
#define MH4U_ORIGIN_PAGE "github.com/khorne-hub/4u-native"
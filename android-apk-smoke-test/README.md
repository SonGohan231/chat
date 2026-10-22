# Android APK Smoke Test

Isolated minimal Android Java test app. Uses Temurin JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, compile SDK 35.

GitHub Actions runs unit tests and builds a debug APK on `ubuntu-latest` x86_64. No Google Play signing key required; debug APK is for manual testing only.

## Local environment (Chromebook ARM64)

The bundled ComputerMCP managed JDK and sdkmanager are separate from PATH. Building with standard Linux x86_64 SDK tools locally is not guaranteed; use GitHub Actions.

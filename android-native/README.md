# Narratium Native (Android)

An **independent native Android app** using Kotlin and Jetpack Compose.
It is *not* a WebView wrapper. It lives alongside the original Next.js website
in the parent repository without changing the web app.

## Features in this initial prototype

- Native Compose character library and chat views.
- Create a character locally or import Narratium / SillyTavern **JSON** cards
  with top-level or nested `data` fields. PNG-embedded cards are **not yet supported**.
- Local chat histories stored in the app's private directory.
- Cancellable streamed chat through a user-configured, OpenAI-compatible
  `/chat/completions` HTTPS endpoint.
- User-provided API key encrypted at rest using Android Keystore AES-GCM.
- A sample character, `薇尔`, is created on first launch.
- No backend account, no cloud sync, no permissions beyond Internet.

## How to build

Open **android-native/** as a Gradle project in Android Studio.
Requirements: JDK 17, Android SDK Platform 36, Gradle 8.13, Kotlin 2.2.21.
This repository does not bundle Gradle wrapper binaries; Android Studio can
import the Gradle project directly. From a host with Gradle 8.13 installed:

```sh
cd android-native
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

The debug APK will appear at:
`android-native/app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow at `.github/workflows/build-android-native.yml`
runs tests and uploads a debug APK as an artifact on qualifying PRs/pushes.
For forked repositories, the owner may first need to enable Actions.

## Install and configure

1. Install the generated debug APK on Android 8.0 (API 26) or later.
2. Open **设置**, enter your compatible API base URL, model ID and API key,
   then tap **保存设置**. Example: `https://api.openai.com/v1`.
3. In **角色**, create a role or import a JSON character file.
4. Select the character and begin chatting. Tap **停止** to cancel streaming.

## Important limitations

- This version is an **MVP**. It does not yet include all the original
  Narratium web features, notably worldbooks, branching dialogues, agent
  generation, plugins, image generation, multi-character management and
  embedded PNG character card import.
- The native database is *not* the web app's IndexedDB. No automatic character
  or history migration takes place.
- Only HTTPS OpenAI-compatible APIs are supported. Local HTTP-only Ollama
  endpoints are not supported in this initial security-first version.
- Messages and character prompts are shared directly with the API provider
  selected by the user. The latest 30 chat messages are included as context.
  Privacy practices depend on that provider.
- Model outputs are shown as plain Compose text, with **no HTML execution**.
- This prototype has not been tested on physical Android devices yet.
  CI should be considered the source of truth for initial build status.

## Security & data lifecycle

- API credentials are stored encrypted at rest by a hardware-backed or
  software-backed Android Keystore key (depending on the device).
- Android backup is disabled for this build; uninstalling removes local
  characters, conversations, and credentials.
- Only use model services that you trust. HTTPS protects in transit,
  but providers still receive the messages that you send.

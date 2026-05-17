# Instally Android SDK

Track clicks, installs, and revenue from every link you share. See which links actually drive installs and revenue for your Android app. No GAID required, no special permissions.

![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-black)
![Kotlin](https://img.shields.io/badge/kotlin-1.8%2B-purple)
![License](https://img.shields.io/badge/license-MIT-black)

**[Website](https://instally.io)** | **[Documentation](https://docs.instally.io)** | **[Blog](https://instally.io/blog)** | **[Sign Up Free](https://app.instally.io/signup)**

## Features

- 3-line integration — configure, track, done
- High-accuracy attribution for Google Play installs
- No GAID/AAID required, no special permissions
- Per-link install and revenue tracking
- Real-time dashboard
- Webhook integrations with RevenueCat, Superwall, Adapty, Qonversion, and Stripe
- Minimal dependency footprint

## Installation

### 1. Add JitPack to your `settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency to your app's `build.gradle.kts`

```kotlin
dependencies {
    implementation("com.github.Instally-io:instally-android-sdk:1.0.1")
}
```

## Quick Start

Three lines of code. In your `Application.onCreate()` or main `Activity.onCreate()`:

```kotlin
import io.instally.sdk.Instally

Instally.configure(this, appId = "APP_ID", apiKey = "API_KEY")
Instally.trackInstall(this)
Instally.setUserId(this, Purchases.sharedInstance.appUserID) // optional — for webhook integrations
```

Get your `appId` and `apiKey` from the [Instally dashboard](https://app.instally.io).

## Track Purchases

After a successful in-app purchase:

```kotlin
Instally.trackPurchase(
    context = this,
    productId = purchase.products.first(),
    revenue = 9.99,
    currency = "USD",
    transactionId = purchase.orderId
)
```

Or use [webhook integrations](https://docs.instally.io) with RevenueCat, Superwall, Adapty, Qonversion, or Stripe for automatic purchase tracking.

## Check Attribution

```kotlin
val attributed = Instally.isAttributed(context)
val id = Instally.attributionId(context)
```

## Testing Attribution

Development builds are supported. For the cleanest test, click the tracking link
once on the same physical device you open the app on, then launch the app within
a few minutes.

Avoid repeated clicks before opening the app. Multiple recent unmatched clicks
from the same device or network can be treated as ambiguous and return
`matched=false`.

`trackInstall()` is cached per app install, including `matched=false` results.
When retrying on the same dev build, uninstall/reinstall the app or clear the SDK
cache in development:

```kotlin
if (BuildConfig.DEBUG) {
    Instally.resetForTesting(context)
}
```

## FAQ

### Do I need any special permissions?

No manifest permissions beyond `INTERNET` (added automatically by the SDK manifest).

### Does it work with RevenueCat or Stripe?

Yes. Call `Instally.setUserId(...)` with your subscription-platform user ID, then configure the Instally webhook in your RevenueCat/Stripe dashboard. Purchases are automatically attributed to the link that drove the install. See the [RevenueCat integration guide](https://instally.io/blog/revenuecat-instally-integration) or [Stripe integration guide](https://instally.io/blog/stripe-instally-integration).

### What happens with preinstalled apps?

If the app was preinstalled (no prior click), `trackInstall()` reports the install as organic.

### What's the SDK size?

Under 50 KB.

### Where can I see my data?

Real-time dashboard at [app.instally.io](https://app.instally.io) — clicks, installs, revenue, per-link breakdown.

## Requirements

- Android API 21+ (Android 5.0)
- Internet permission (added automatically by the SDK manifest)

## Learn More

- [How to Track App Installs in Android (Kotlin)](https://instally.io/blog/how-to-track-app-installs-android) — full integration walkthrough
- [Instally vs AppsFlyer vs Branch](https://instally.io/blog/instally-vs-appsflyer-vs-branch) — competitor comparison

## Resources

- [Instally Website](https://instally.io) — Track clicks, installs, and revenue from every link
- [Dashboard](https://app.instally.io) — Real-time analytics for your app installs
- [Documentation](https://docs.instally.io) — Full SDK docs and API reference
- [Pricing](https://instally.io/pricing) — Free tier available, no credit card required
- [Blog](https://instally.io/blog) — Guides on install tracking, IDFA, and more

### Other SDKs

- [iOS SDK](https://github.com/Instally-io/instally-ios-sdk)
- [Flutter SDK](https://github.com/Instally-io/instally-flutter-sdk)
- [React Native SDK](https://github.com/Instally-io/instally-react-native-sdk)

## License

MIT

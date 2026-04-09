# Instally Android SDK

Track clicks, installs, and revenue from every link you share. See which links actually drive installs and revenue for your Android app. Deterministic attribution via Google Install Referrer with fingerprint fallback.

**[Website](https://instally.io)** | **[Documentation](https://docs.instally.io)** | **[Blog](https://instally.io/blog)** | **[Sign Up Free](https://app.instally.io/signup)**

## Features

- 3-line integration — configure, track, done
- Deterministic attribution via Google Play Install Referrer
- No GAID/AAID required, no special permissions
- Per-link install and revenue tracking
- Real-time dashboard
- Webhook integrations with RevenueCat, Superwall, Adapty, Qonversion, and Stripe
- Single dependency (Install Referrer)

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
    implementation("com.github.instally:instally-android-sdk:1.0.0")
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

## How It Works

1. On first launch, the SDK reads the Google Play Install Referrer (deterministic) and collects device signals
2. Signals are sent to the Instally attribution API
3. The API matches the install to a tracking link click
4. The result is cached locally — `trackInstall()` only fires once per install
5. Purchases are linked to the attribution via `trackPurchase()` or webhooks

## Requirements

- Android API 21+ (Android 5.0)
- Internet permission (added automatically by the SDK manifest)

## Resources

- [Getting started guide](https://docs.instally.io/quick-start)
- [Instally website](https://instally.io)
- [Instally blog](https://instally.io/blog)

## License

MIT

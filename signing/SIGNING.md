# Release signing (com.ouor.mobilegit)

Instructions for agents signing this app. Follow them exactly.

## Files (all in `signing/` at the repo root)

| File | Purpose | Git |
|---|---|---|
| `release.p12` | PKCS#12 keystore with the private signing key, alias `release` | **ignored, never commit** |
| `keystore.properties` | `storePassword=...` for the keystore (key password is the same) | **ignored, never commit** |
| `release.crt` | Signing certificate (public) | committed |
| `SIGNING.md` | This file | committed |

Expected signing certificate SHA-256: `0b2182b8d742e61a72c6664af6528e5e11c842972c84d092b0652059d479378c`

## Rules

- Never commit, print, log, copy elsewhere, or upload `release.p12` or `keystore.properties`, or the password in them.
- Never generate a new key, keystore, or certificate, and never edit `release.crt`. Keys are derived offline by the owner; a different certificate breaks app updates permanently.
- If `release.p12` or `keystore.properties` is missing or the password fails, stop and ask the owner to re-export them. Do not work around it.
- Always verify the certificate digest of the signed output (see below) before reporting success.

## .gitignore

```
signing/release.p12
signing/keystore.properties
```

## Gradle (Kotlin DSL, app/build.gradle.kts)

```kotlin
val ksProps = java.util.Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
android {
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/release.p12")
            storeType = "PKCS12"
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = "release"
            keyPassword = ksProps.getProperty("storePassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}
```

Then `./gradlew assembleRelease` (APK) or `./gradlew bundleRelease` (AAB).

## Manual APK signing (apksigner)

```bash
export STOREPASS="$(sed -n 's/^storePassword=//p' signing/keystore.properties)"
apksigner sign --ks signing/release.p12 --ks-type PKCS12 --ks-key-alias release \
  --ks-pass env:STOREPASS --key-pass env:STOREPASS --out app-release-signed.apk app-release-unsigned.apk
unset STOREPASS
```

Run `zipalign` before `apksigner` if the APK is not already aligned. The key is ECDSA P-256 (requires minSdk 18+ for v1 signatures).

## Verification

```bash
apksigner verify --print-certs app-release-signed.apk | grep 'SHA-256 digest'
```

The digest must equal `0b2182b8d742e61a72c6664af6528e5e11c842972c84d092b0652059d479378c`. If it does not, the build was signed with the wrong key: do not distribute it.

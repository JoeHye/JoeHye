# Releasing to Google Play

Two kinds of builds come out of CI:

| Build | Workflow | Signed with | Use |
|---|---|---|---|
| Debug APK | `Android build` (every push) | committed `app/debug.keystore` | testing; installed from the `latest-debug` release link |
| Release bundle (`.aab`) | `Release bundle` (tag `v*` or manual run) | your private **upload key** (GitHub secrets) | uploading to Google Play |

The debug key is public. Never use it for Play.

## One-time setup: create the upload key

Do this on your own computer, not in a shared or cloud environment. You need Java installed
(Android Studio includes `keytool`; on Windows it's in `C:\Program Files\Android\Android Studio\jbr\bin`).

```sh
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a keystore password, then your name/organisation (any values), then a key password
(pressing Enter reuses the keystore password).

**Back up `upload-keystore.jks` and both passwords** somewhere safe (a password manager). With Play App
Signing (the default), Google holds the real app-signing key, so a lost upload key can be reset
through Play Console support — but that takes days and blocks updates meanwhile.

## One-time setup: add the GitHub secrets

1. Turn the keystore into base64 text:
   - macOS / Linux: `base64 -i upload-keystore.jks | tr -d '\n' > upload-keystore.b64`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks")) > upload-keystore.b64`
2. In GitHub: repository **Settings → Secrets and variables → Actions → New repository secret**, add:

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | contents of `upload-keystore.b64` |
   | `RELEASE_KEYSTORE_PASSWORD` | keystore password |
   | `RELEASE_KEY_ALIAS` | `upload` (or the alias you chose) |
   | `RELEASE_KEY_PASSWORD` | key password |

3. Delete `upload-keystore.b64` afterwards; keep only the backed-up `.jks`.

## Making a release

1. Push a tag: `git tag v1.0.0 && git push origin v1.0.0`
   (or run **Actions → Release bundle → Run workflow**).
2. When it finishes, download the `release-aab-signed` artifact from the run page and upload the `.aab`
   in Play Console. Also keep the `r8-mapping` artifact (Play Console → App bundle explorer → upload
   the mapping file) so crash reports show readable stack traces.

`versionCode` is the workflow's run number, so every bundle is higher than the last; `versionName` comes
from `app/build.gradle.kts` (bump it for user-visible versions).

### What failures mean

| Message | Cause |
|---|---|
| `RELEASE_KEYSTORE_BASE64 secret is not set; refusing to build an unsigned bundle for a release tag` | Secrets missing; a tag build never produces an unsigned bundle |
| `No signing secrets configured; building an UNSIGNED bundle` (warning) | Manual run without secrets; the bundle can't be uploaded to Play |
| `RELEASE_KEYSTORE_BASE64 is not valid base64` | Secret was pasted with extra characters; redo step 1 |
| `RELEASE_..._PASSWORD must be set when RELEASE_KEYSTORE_PATH is` | Keystore secret present but a password/alias secret is missing or empty |
| `Keystore was tampered with, or password was incorrect` | Wrong `RELEASE_KEYSTORE_PASSWORD` |
| `Cannot recover key` | Wrong `RELEASE_KEY_PASSWORD` |

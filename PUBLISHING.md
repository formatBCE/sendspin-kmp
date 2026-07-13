# Publishing `sendspin-protocol` to Maven Central

This library is published to **Maven Central** via the **Central Portal**, built on a **macOS**
GitHub Actions runner (required — the `iosArm64` / `iosSimulatorArm64` targets need Xcode, which is
why JitPack cannot be used).

- **Coordinates:** `io.github.formatbce:sendspin-protocol:<version>`
- **Kotlin package (unchanged):** `com.sendspin.protocol` — consumer `import`s are unaffected by the
  Maven group.
- **Version:** set in the root `build.gradle.kts` (`allprojects { version = "…" }`). Central rejects
  `-SNAPSHOT` on the release endpoint, so releases use plain semver (`0.1.0`, `0.1.1`, …).

The Gradle wiring (vanniktech `com.vanniktech.maven.publish`, POM, signing, all KMP targets) is
already done. What remains is **one-time account/secret setup**, then **tag to release**.

---

## One-time setup

### 1. Central Portal account + namespace
1. Sign in at <https://central.sonatype.com/> (GitHub login is fine).
2. Register the namespace **`io.github.formatbce`** → it prompts you to verify ownership of the
   GitHub account `formatBCE` (create a public repo with a given name, or a TXT — follow its
   instructions). This is free and usually instant for `io.github.*`.
3. Under **Account → Generate User Token**, create a token. You get a **username** and **password**
   pair — these are the Portal publishing credentials (NOT your login).

### 2. GPG signing key (Central requires signed artifacts)
```bash
# Generate a key (use a real name/email; pick a passphrase or leave empty)
gpg --full-generate-key            # choose RSA 4096, no expiry is fine

# Find the key id
gpg --list-secret-keys --keyid-format=long

# Publish the PUBLIC key to a keyserver (Central verifies signatures against it)
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>

# Export the ARMORED PRIVATE key — this whole block is the SIGNING_KEY secret
gpg --armor --export-secret-keys <LONG_KEY_ID>
```

### 3. GitHub repo secrets
In `formatBCE/sendspin-kmp` → **Settings → Secrets and variables → Actions → New repository secret**,
add four:

| Secret name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal user-token **username** (step 1.3) |
| `MAVEN_CENTRAL_PASSWORD` | Portal user-token **password** (step 1.3) |
| `SIGNING_KEY` | full armored private key block from `gpg --armor --export-secret-keys` (step 2) |
| `SIGNING_KEY_PASSWORD` | the key's passphrase (empty string if you set none) |

---

## Cutting a release

```bash
# 1. Bump the version in build.gradle.kts (allprojects { version = "0.1.0" }), commit.
# 2. Tag and push — this triggers .github/workflows/publish.yml on macos-latest.
git tag v0.1.0
git push origin v0.1.0
```

`automaticRelease = true` means the deployment is validated and released automatically; no manual
"Publish" click in the Portal. Propagation to `repo1.maven.org` / consumers typically takes
**~15–30 min** after the workflow succeeds. You can also trigger a run manually from the Actions tab
(**workflow_dispatch**).

### Local dry-check
`./gradlew :sendspin-protocol:publishToMavenLocal` builds and stages all target artifacts to
`~/.m2` (it will fail only at the signature step if you have no GPG key configured — that's
expected; the artifacts themselves are produced).

---

## Consuming from the app

Once the first version is live on Central, switch `MusicAssistantClient` off the composite build:

- **`settings.gradle.kts`** — remove (or comment) `includeBuild("../sendspin-kmp")`.
- **`composeApp/build.gradle.kts`** — change the dependency to:
  ```kotlin
  implementation("io.github.formatbce:sendspin-protocol:0.1.0")
  ```
  (`mavenCentral()` is already in the app's repositories.)

Do this **only after** the artifact resolves on Central, or the app build will fail to find it.

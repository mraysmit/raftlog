# RaftLog Maven Release Guide

This document provides the exact, repo-specific process for publishing RaftLog to Maven Central via Sonatype Central Portal.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Release At A Glance](#release-at-a-glance)
3. [Deploying to Maven Central](#deploying-to-maven-central)
4. [GPG Signing Setup](#gpg-signing-setup)
5. [Troubleshooting](#troubleshooting)
6. [Release Checklist](#release-checklist)

---

## Prerequisites

Before performing a release, ensure you have:

- **Java 21+** installed and configured
- **Maven 3.8.0+** installed
- **Git** configured with push access to the repository
- **GPG** installed (for signing releases)
- **Sonatype Central Portal token** (for Maven Central deployment)

### Verify Your Environment

```bash
# Check Java version (must be 21+)
java -version

# Check Maven version (must be 3.8.0+)
mvn -version

# Check GPG installation
gpg --version

# Verify project builds successfully
mvn clean verify
```

---

## Release At A Glance

This is the recommended release path for this repo.

Windows optional cleanup before deploy:

```powershell
Stop-Process -Name "gpg" -Force -ErrorAction SilentlyContinue
Remove-Item -Force -Recurse ".\target" -ErrorAction SilentlyContinue
```

```bash
# 1) Verify state
git status
mvn clean verify

# 2) Set release version
mvn versions:set -DnewVersion=X.Y.Z
mvn versions:commit

# 3) Commit version bump
git add -A
git commit -m "Release X.Y.Z"

# 4) Publish to Central Portal
mvn -Prelease -DskipTests clean deploy

# 5) Tag and publish git refs
git tag vX.Y.Z
git push origin main
git push origin vX.Y.Z

# 6) Create GitHub release notes
gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes
```

Notes:
1. Central versions are immutable. If `X.Y.Z` already exists, bump and retry.
2. Keep publishing plugin in main build plugins (not in an inactive profile).
3. Do not add legacy OSSRH `distributionManagement` URLs.

---

## Deploying to Maven Central

This project publishes through Sonatype Central Portal (not legacy OSSRH staging).

### Project Coordinates

- Namespace: `io.github.mraysmit`
- Parent artifact: `io.github.mraysmit:raftlog`
- Core artifact: `io.github.mraysmit:raftlog-core`
- Demo artifact: `io.github.mraysmit:raftlog-demo`

### One-Time Setup

1. Verify namespace ownership in Central Portal for `io.github.mraysmit`.
2. Generate a GPG key and ensure UID uses correct email format:
     - Correct: `Name <email@example.com>`
     - Incorrect: `Name email@example.com`
3. Publish your public key to keys.openpgp.org and complete email verification.
4. Create a Central Portal publishing token.
5. Configure Maven settings with server id `central` and GPG properties.

### Maven Settings for Central Portal

Add this to `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>${env.CENTRAL_TOKEN_USER}</username>
            <password>${env.CENTRAL_TOKEN_PASS}</password>
        </server>
    </servers>

    <profiles>
        <profile>
            <id>gpg</id>
            <properties>
                <gpg.executable>C:\Program Files\GnuPG\bin\gpg.exe</gpg.executable>
                <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
                <gpg.passphrase>${env.GPG_PASSPHRASE}</gpg.passphrase>
            </properties>
        </profile>
    </profiles>

    <activeProfiles>
        <activeProfile>gpg</activeProfile>
    </activeProfiles>
</settings>
```

Note: For local-only testing you can keep the passphrase out of settings and use gpg-agent. For unattended Windows release runs, setting `gpg.passphrase` is often required.

### Release Runbook (Validated Workflow)

Use this exact sequence for publishing a new release.

```bash
# 1) Ensure clean branch and verify build
git status
mvn clean verify

# 2) Set release version (example: 1.1.1)
mvn versions:set -DnewVersion=1.1.1
mvn versions:commit

# 3) Commit version bump
git add -A
git commit -m "Release 1.1.1"

# 4) Publish to Central Portal
mvn -Prelease -DskipTests clean deploy

# 5) Tag and push after successful publish
git tag v1.1.1
git push origin main
git push origin v1.1.1
```

### Windows Pre-Deploy Cleanup (Recommended)

If previous runs left locked signature files, run this before deploy:

```powershell
Stop-Process -Name "gpg" -Force -ErrorAction SilentlyContinue
Remove-Item -Force -Recurse ".\target" -ErrorAction SilentlyContinue
```

### Important Central Portal Rules

1. Artifact versions are immutable. If `1.1.0` exists, publish `1.1.1`.
2. Do not use legacy OSSRH `distributionManagement` URLs in this project.
3. The Central publishing plugin must be active in main build plugins, not hidden in an inactive profile.
4. If Central says your key is missing, verify key publication and UID email format first.

---

## GPG Signing Setup

### Generate a GPG Key

```bash
# Generate a new key pair
gpg --gen-key

# List your keys
gpg --list-secret-keys --keyid-format LONG

# Export public key (for publishing)
gpg --armor --export YOUR_KEY_ID > public-key.asc
```

### Publish Your Public Key

Preferred method:
1. Export the key as ASCII armor.
2. Upload at https://keys.openpgp.org/upload.
3. Complete the verification email flow.

Fallback (if HKP/HKPS is available):

```bash
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

Note: On some corporate/home networks, keyserver protocol traffic is blocked; web upload still works.

### Configure GPG for Maven

In `~/.m2/settings.xml`:

```xml
<profiles>
    <profile>
        <id>gpg</id>
        <properties>
            <gpg.executable>C:\Program Files\GnuPG\bin\gpg.exe</gpg.executable>
            <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
            <gpg.passphrase>${env.GPG_PASSPHRASE}</gpg.passphrase>
        </properties>
    </profile>
</profiles>

<activeProfiles>
    <activeProfile>gpg</activeProfile>
</activeProfiles>
```

### Verify Signing Works Before Deploy

```bash
echo test | gpg --clearsign
mvn -Prelease -DskipTests clean install
```

### GPG Agent for Passphrase Caching (Optional)

To avoid entering your passphrase repeatedly:

```bash
# Start GPG agent (usually automatic on modern systems)
gpg-agent --daemon

# Configure in ~/.gnupg/gpg-agent.conf
default-cache-ttl 3600
max-cache-ttl 7200
```

### Skip GPG Signing (Development Only)

```bash
# Skip GPG signing for local testing
mvn clean install -Prelease -Dgpg.skip=true
```

---

## Troubleshooting

### Common Issues

#### 1. "Could not find a public key by the key fingerprint"

- Confirm Maven is signing with the expected key fingerprint.
- Publish key to https://keys.openpgp.org/upload and complete email verification.
- Ensure UID email is in `Name <email@domain>` format.
- Re-upload key after any UID changes.

#### 2. "Component with package url already exists"

The version already exists in Central. Bump and redeploy:

```bash
mvn versions:set -DnewVersion=X.Y.(Z+1)
mvn versions:commit
mvn -Prelease -DskipTests clean deploy
```

#### 3. GPG hangs or `.asc` files are locked on Windows

```powershell
Stop-Process -Name "gpg" -Force -ErrorAction SilentlyContinue
Remove-Item -Force -Recurse ".\target" -ErrorAction SilentlyContinue
```

Also verify in `~/.m2/settings.xml`:
- `gpg.executable` is the full Windows path
- `gpg.keyname` matches your key
- `gpg.passphrase` is available (often required for unattended runs)

#### 4. "401 Unauthorized" during deploy

- Verify `server.id` is `central`
- Verify Central token username/password
- Regenerate token if necessary

#### 5. Local-only build without GPG

```powershell
mvn clean install -Prelease -DskipTests "-Dgpg.skip=true"
```

Use only for local development, not Central publishing.

### Security Note: Encrypted Maven Passwords

```bash
mvn --encrypt-master-password your-master-password
mvn --encrypt-password your-central-token-password
```

---

## Release Checklist

Before releasing, verify:

- [ ] All tests pass: `mvn clean verify`
- [ ] No SNAPSHOT dependencies: `mvn dependency:tree` shows no `SNAPSHOT` artifacts
- [ ] Documentation is updated
- [ ] CHANGELOG is updated
- [ ] Version numbers are correct
- [ ] Git working directory is clean
- [ ] GPG key is available and not expired
- [ ] Central token credentials are configured (`server.id = central`)
- [ ] Version does not already exist in Central
- [ ] Git tag and GitHub release created after publish

## Additional Resources

- [Central Portal Publishing Guide](https://central.sonatype.org/publish/publish-portal/)
- [GPG Best Practices](https://riseup.net/en/security/message-security/openpgp/best-practices)
- [Maven Password Encryption](https://maven.apache.org/guides/mini/guide-encryption.html)

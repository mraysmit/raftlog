# RaftLog Maven Release Guide

This document provides comprehensive instructions for building release versions and deploying the RaftLog project to Maven repositories.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Build Profiles](#build-profiles)
3. [Quick Release Build](#quick-release-build)
4. [Full Maven Release Process](#full-maven-release-process)
5. [Manual Version Management](#manual-version-management)
6. [Deploying to Maven Central](#deploying-to-maven-central)
7. [GPG Signing Setup](#gpg-signing-setup)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

Before performing a release, ensure you have:

- **Java 21+** installed and configured
- **Maven 3.8.0+** installed
- **Git** configured with push access to the repository
- **GPG** installed (for signing releases)
- **OSSRH Account** (for Maven Central deployment)

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

## Build Profiles

The RaftLog parent POM includes several profiles for different build scenarios:

| Profile | Description | Usage |
|---------|-------------|-------|
| `release` | Generates sources, javadocs, and signs artifacts | `-Prelease` |
| `quick` | Skips tests and static analysis for fast builds | `-Pquick` |
| `ci` | CI/CD optimized build with coverage | `-Pci` |
| `coverage` | Generates JaCoCo code coverage reports | `-Pcoverage` |
| `core-tests` | Runs only core unit tests | `-Pcore-tests` |
| `integration-tests` | Runs integration tests | `-Pintegration-tests` |
| `performance-tests` | Runs performance tests | `-Pperformance-tests` |
| `all-tests` | Runs all test categories | `-Pall-tests` |

---

## Quick Release Build

For a local release build without deploying to a repository:

```bash
# Build release with all artifacts (sources, javadocs, signed)
mvn clean install -Prelease

# Build release skipping tests (use with caution)
mvn clean install -Prelease -DskipTests

# Build release with specific module
mvn clean install -Prelease -pl raftlog-core
```

### What the Release Profile Does

1. **Source JAR**: Packages source code for distribution
2. **Javadoc JAR**: Generates and packages API documentation
3. **GPG Signing**: Signs all artifacts (if GPG is configured)
4. **Reproducible Builds**: Ensures consistent artifact generation

---

## Full Maven Release Process

The Maven Release Plugin automates version management, tagging, and deployment.

### Step 1: Prepare the Release

```bash
# Dry run first (recommended)
mvn release:prepare -DdryRun=true

# If dry run succeeds, clean up and do the real prepare
mvn release:clean
mvn release:prepare
```

During `release:prepare`, Maven will:
1. Verify there are no uncommitted changes
2. Verify there are no SNAPSHOT dependencies
3. Prompt for release version (default: remove -SNAPSHOT)
4. Prompt for SCM tag name (default: artifactId-version)
5. Prompt for next development version (default: increment and add -SNAPSHOT)
6. Update POMs with release version
7. Commit the modified POMs
8. Tag the release in SCM
9. Update POMs with next development version
10. Commit the modified POMs

### Step 2: Perform the Release

```bash
mvn release:perform
```

During `release:perform`, Maven will:
1. Checkout the tagged release
2. Build the release
3. Deploy to the configured repository

### Step 3: If Something Goes Wrong

```bash
# Roll back a failed release
mvn release:rollback

# Clean up release plugin files
mvn release:clean
```

### Non-Interactive Release

For CI/CD pipelines, use batch mode:

```bash
mvn release:prepare release:perform -B \
    -DreleaseVersion=1.0.0 \
    -DdevelopmentVersion=1.1.0-SNAPSHOT \
    -Dtag=v1.0.0
```

---

## Manual Version Management

For more control over the release process:

### Update Version Numbers

```bash
# Set a specific version across all modules
mvn versions:set -DnewVersion=1.0.0

# Commit the changes if satisfied
mvn versions:commit

# Or revert if not satisfied
mvn versions:revert
```

### Build and Tag Manually

```bash
# 1. Set release version
mvn versions:set -DnewVersion=1.0.0
mvn versions:commit

# 2. Build the release
mvn clean install -Prelease

# 3. Create Git tag
git add -A
git commit -m "Release 1.0.0"
git tag -a v1.0.0 -m "Release version 1.0.0"

# 4. Deploy (if desired)
mvn deploy -Prelease

# 5. Set next development version
mvn versions:set -DnewVersion=1.1.0-SNAPSHOT
mvn versions:commit
git add -A
git commit -m "Prepare for next development iteration"

# 6. Push everything
git push origin main
git push origin v1.0.0
```

---

## Deploying to Maven Central

### Prerequisites for Maven Central

1. **OSSRH Account**: Register at https://issues.sonatype.org
2. **Group ID Verification**: Claim your group ID (dev.mars.raftlog)
3. **GPG Key**: Generate and publish a GPG signing key
4. **Maven Settings**: Configure credentials

### Configure Maven Settings

Add to your `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>ossrh</id>
            <username>your-ossrh-username</username>
            <password>your-ossrh-password</password>
        </server>
    </servers>
    
    <profiles>
        <profile>
            <id>ossrh</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <gpg.executable>gpg</gpg.executable>
                <gpg.keyname>your-key-id</gpg.keyname>
            </properties>
        </profile>
    </profiles>
</settings>
```

### Deploy Snapshot Versions

```bash
# Deploy current SNAPSHOT to OSSRH snapshots repository
mvn clean deploy
```

### Deploy Release Versions

```bash
# Deploy release to OSSRH staging
mvn clean deploy -Prelease

# Or use the release plugin
mvn release:prepare release:perform
```

### Release from OSSRH Staging

After deploying to staging:

1. Log in to https://oss.sonatype.org
2. Find your staging repository under "Staging Repositories"
3. Verify the contents
4. Click "Close" to trigger validation
5. If validation passes, click "Release"
6. Artifacts will sync to Maven Central within ~10 minutes

### Automatic Release (Skip Staging)

For automatic release after deployment:

```bash
mvn clean deploy -Prelease -Dautorelease=true
```

---

## GPG Signing Setup

### Generate a GPG Key

```bash
# Generate a new key pair
gpg --gen-key

# List your keys
gpg --list-keys

# Export public key (for publishing)
gpg --armor --export your-key-id > public-key.asc
```

### Publish Your Public Key

```bash
# Publish to a key server
gpg --keyserver keyserver.ubuntu.com --send-keys your-key-id

# Or publish to multiple servers
gpg --keyserver keys.openpgp.org --send-keys your-key-id
gpg --keyserver pgp.mit.edu --send-keys your-key-id
```

### Configure GPG for Maven

In `~/.m2/settings.xml`:

```xml
<profiles>
    <profile>
        <id>gpg</id>
        <properties>
            <gpg.executable>gpg</gpg.executable>
            <gpg.keyname>your-key-id</gpg.keyname>
            <!-- Optional: passphrase (not recommended for security) -->
            <!-- <gpg.passphrase>your-passphrase</gpg.passphrase> -->
        </properties>
    </profile>
</profiles>

<activeProfiles>
    <activeProfile>gpg</activeProfile>
</activeProfiles>
```

### GPG Agent for Passphrase Caching

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

#### 1. "Cannot prepare the release because you have local modifications"

```bash
# Check for uncommitted changes
git status

# Commit or stash changes
git add -A && git commit -m "Prepare for release"
# or
git stash
```

#### 2. "Cannot resolve project dependencies"

```bash
# Update snapshots and rebuild
mvn clean install -U
```

#### 3. GPG Signing Fails

```bash
# Test GPG signing
echo "test" | gpg --clearsign

# Check GPG agent is running
gpg-agent --daemon

# Specify key explicitly
mvn clean install -Prelease -Dgpg.keyname=your-key-id
```

#### 4. "401 Unauthorized" During Deploy

- Verify OSSRH credentials in `~/.m2/settings.xml`
- Check server ID matches (`ossrh`)
- Ensure password is correct (consider using encrypted passwords)

#### 5. Javadoc Generation Fails

```bash
# Check for Javadoc errors
mvn javadoc:javadoc

# Skip Javadoc for troubleshooting
mvn clean install -Prelease -Dmaven.javadoc.skip=true
```

#### 6. Release Plugin Issues

```bash
# Clean up release plugin files
mvn release:clean

# Reset to clean state
git reset --hard HEAD
git clean -fd
```

### Encrypted Passwords

For better security, use Maven password encryption:

```bash
# Create master password
mvn --encrypt-master-password your-master-password

# Add to ~/.m2/settings-security.xml
<settingsSecurity>
    <master>{encrypted-master-password}</master>
</settingsSecurity>

# Encrypt your OSSRH password
mvn --encrypt-password your-ossrh-password

# Use encrypted password in settings.xml
<server>
    <id>ossrh</id>
    <username>your-username</username>
    <password>{encrypted-password}</password>
</server>
```

---

## Release Checklist

Before releasing, verify:

- [ ] All tests pass: `mvn clean verify`
- [ ] No SNAPSHOT dependencies: `mvn dependency:tree | grep SNAPSHOT`
- [ ] Documentation is updated
- [ ] CHANGELOG is updated
- [ ] Version numbers are correct
- [ ] Git working directory is clean
- [ ] GPG key is available and not expired
- [ ] OSSRH credentials are configured

## Quick Reference

| Task | Command |
|------|---------|
| Local release build | `mvn clean install -Prelease` |
| Deploy snapshot | `mvn clean deploy` |
| Deploy release | `mvn clean deploy -Prelease` |
| Full release | `mvn release:prepare release:perform` |
| Set version | `mvn versions:set -DnewVersion=X.Y.Z` |
| Skip GPG | `-Dgpg.skip=true` |
| Skip tests | `-DskipTests` |
| Batch mode | `-B` |

---

## Additional Resources

- [Maven Release Plugin Documentation](https://maven.apache.org/maven-release/maven-release-plugin/)
- [OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [GPG Best Practices](https://riseup.net/en/security/message-security/openpgp/best-practices)
- [Maven Password Encryption](https://maven.apache.org/guides/mini/guide-encryption.html)

# TDLib Adapter Setup Guide

This guide explains how to set up and run the TDLib Adapter for connecting EMCIP to Telegram as a real user client.

## Prerequisites

1. **Telegram API Credentials**
   - Visit https://my.telegram.org/apps
   - Log in with your phone number
   - Create a new application
   - Note your `api_id` (integer) and `api_hash` (string)

2. **System Dependencies**
   - TDLib native library (`libtdjni.so` on Linux, `libtdjni.dylib` on macOS, `tdjni.dll` on Windows)

## Getting TDLib Java Bindings

**Current State:** The project includes stub classes in `org.drinkless.tdlib` for compilation only. At
runtime, the real TDLib native library (`libtdjni.so`) must be present. The Dockerfile handles this
automatically by building TDLib from source inside Docker.

> **Note:** TDLib does **not** publish pre-built JARs on GitHub releases. The GitHub releases page
> contains source archives only. You must build from source or use Docker (recommended).

### Option 1: Docker (Recommended) — No manual TDLib setup required

The `emcip-tdlib-adapter/Dockerfile` is a multi-stage build that:
1. Clones TDLib v1.8.29 and builds the native library (`libtdjni.so`)
2. Replaces the compile-time stubs with real generated Java sources
3. Packages everything into a self-contained JRE image

**Setup:**

```bash
# 1. Copy the environment template and fill in your credentials
cp .env.example .env
# Edit .env and set TELEGRAM_API_ID and TELEGRAM_API_HASH

# 2. Start infrastructure + tdlib-adapter (first build takes 15-30 min)
docker compose --profile telegram up -d

# 3. Authenticate (see "Authentication" section below)
curl http://localhost:9080/api/auth/status
```

The build result is cached in Docker layer cache — subsequent starts are fast.

**Multi-arch builds** (Apple Silicon, ARM servers):

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t emcip-tdlib-adapter:latest \
  ./emcip-tdlib-adapter
```

QEMU handles cross-compilation automatically when buildx is configured.

### Option 2: Build from Source (non-Docker, local development)

**Prerequisites:**
- C++ compiler (GCC 7+ or Clang 5+)
- CMake 3.8+
- OpenSSL development libraries
- zlib development libraries

**Build Steps:**

```bash
# 1. Clone TDLib repository
git clone https://github.com/tdlib/td.git
cd td

# 2. Checkout stable version (match our stubs: 1.8.x)
git checkout v1.8.29

# 3. Build with Java bindings
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release \
  -DTD_ENABLE_JNI=ON \
  -DJAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ..

# 4. Compile (this takes 10-30 minutes)
cmake --build . --target install -j$(nproc)

# 5. Find the built artifacts
# Java JAR: build/tdlib/bin/tdlib.jar
# Native lib: build/tdlib/bin/libtdjni.so (Linux)
```

### Option 3: Use Package Manager (Limited Availability)

```bash
# Ubuntu/Debian (may be older version)
sudo apt-get install libtdlib-dev

# macOS with Homebrew
brew install tdlib
```

> **Warning:** Package managers typically install an older TDLib version and may **not** include the
> Java JNI bindings (`libtdjni.so`). Verify with: `find /usr -name 'libtdjni*' 2>/dev/null`
> If the library is absent, use Option 1 (Docker) or Option 2 (build from source) instead.

## Integrating Real TDLib

### Step 1: Replace Stubs

Delete stub files and use real library:

```bash
# Remove stubs
rm -rf emcip-tdlib-adapter/src/main/java/org/drinkless/

# Copy real JAR
cp /path/to/built/or/downloaded/tdlib.jar emcip-tdlib-adapter/lib/
```

### Step 2: Update pom.xml

Uncomment and modify the TDLib dependency:

```xml
<!-- In emcip-tdlib-adapter/pom.xml -->
<dependency>
  <groupId>org.drinkless</groupId>
  <artifactId>tdlib</artifactId>
  <version>1.8.29</version>
  <scope>system</scope>
  <systemPath>${project.basedir}/lib/tdlib.jar</systemPath>
</dependency>
```

### Step 3: Configure Native Library Path

Set JVM argument to find native library:

```bash
# Linux
export LD_LIBRARY_PATH=/path/to/tdlib/lib:$LD_LIBRARY_PATH

# macOS
export DYLD_LIBRARY_PATH=/path/to/tdlib/lib:$DYLD_LIBRARY_PATH

# Windows (in command prompt)
set PATH=C:\path\to\tdlib\lib;%PATH%
```

Or via JVM argument:

```bash
java -Djava.library.path=/path/to/tdlib/lib -jar app.jar
```

### Step 4: Install to Local Maven (Optional but Recommended)

```bash
# Install to local Maven repo for cleaner dependency management
mvn install:install-file \
  -Dfile=emcip-tdlib-adapter/lib/tdlib.jar \
  -DgroupId=org.drinkless \
  -DartifactId=tdlib \
  -Dversion=1.8.29 \
  -Dpackaging=jar
```

Then update pom.xml to regular dependency:

```xml
<dependency>
  <groupId>org.drinkless</groupId>
  <artifactId>tdlib</artifactId>
  <version>1.8.29</version>
</dependency>
```

## Verification

Test that real TDLib loads correctly:

```bash
cd emcip-tdlib-adapter
mvn clean compile
LD_LIBRARY_PATH=lib java -cp target/classes:lib/tdlib.jar \
  -Djava.library.path=lib \
  org.drinkless.tdlib.Client
```

Should show no "ClassNotFound" or "UnsatisfiedLinkError".

## Important URLs

| Resource | URL |
|----------|-----|
| TDLib GitHub | https://github.com/tdlib/td |
| TDLib Java Example | https://github.com/tdlib/td/tree/master/example/java |
| TDLib Docs | https://core.telegram.org/tdlib/docs/ |
| Telegram API | https://core.telegram.org/api |
| Building TDLib | https://tdlib.github.io/td/build.html |
| Releases | https://github.com/tdlib/td/releases |

## Configuration

### Environment Variables

Set these environment variables before running:

```bash
export TELEGRAM_API_ID=your_api_id_here        # e.g., 12345678
export TELEGRAM_API_HASH=your_api_hash_here    # e.g., abcdef1234567890abcdef1234567890
export TELEGRAM_PHONE_NUMBER=+1234567890      # Your phone number with country code
export KAFKA_BOOTSTRAP_SERVERS=localhost:14003  # Kafka broker address (external)
```

### Optional Configuration

```bash
export TDLIB_DB_DIR=tdlib-db                   # TDLib database directory
export TDLIB_FILES_DIR=tdlib-files             # TDLib files directory
```

## Running Locally

### 1. Start Infrastructure

```bash
# Infrastructure only (Kafka, PostgreSQL, etc.)
docker compose up -d kafka zookeeper postgres

# Or with tdlib-adapter via Docker (recommended):
docker compose --profile telegram up -d
```

### 2. Run the Adapter

```bash
cd emcip-tdlib-adapter
mvn spring-boot:run
```

Or with explicit environment:

```bash
TELEGRAM_API_ID=12345678 \
TELEGRAM_API_HASH=abcdef1234567890abcdef1234567890 \
TELEGRAM_PHONE_NUMBER=+1234567890 \
mvn spring-boot:run
```

### 3. Authenticate

Check authentication status:

```bash
curl http://localhost:9080/api/auth/status
```

If not authorized, submit your phone number:

```bash
curl -X POST http://localhost:9080/api/auth/phone \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+1234567890"}'
```

You'll receive an authentication code via Telegram. Submit it:

```bash
curl -X POST http://localhost:9080/api/auth/code \
  -H "Content-Type: application/json" \
  -d '{"code": "12345"}'
```

If you have 2FA enabled, submit your password:

```bash
curl -X POST http://localhost:9080/api/auth/password \
  -H "Content-Type: application/json" \
  -d '{"password": "your_2fa_password"}'
```

### 4. Verify Connection

Check health endpoint:

```bash
curl http://localhost:9080/actuator/health
```

You should see:
```json
{
  "status": "UP",
  "components": {
    "tdlib": {
      "status": "UP",
      "details": {
        "tdlib": "connected and authorized",
        "authorization": "complete"
      }
    }
  }
}
```

### 5. Monitor Events

Watch for messages in Kafka:

```bash
docker exec -it ecip-kafka kafka-console-consumer \
  --topic telegram.raw.messages \
  --from-beginning \
  --bootstrap-server localhost:14003
```

## Troubleshooting

### `UnsatisfiedLinkError: no tdjni in java.library.path`

This is the most common failure. It means the TDLib native library is not found at runtime.

**If running via Docker:** The Dockerfile handles this automatically. Ensure you're using the
provided `Dockerfile` (not a custom one). Rebuild if needed:
```bash
docker compose build tdlib-adapter
```

**If running locally (mvn spring-boot:run):** You need to build TDLib from source and point the
JVM to the native library:
```bash
# After building TDLib (see Option 2 above), run with:
LD_LIBRARY_PATH=/path/to/td/build java \
  -Djava.library.path=/path/to/td/build \
  -jar target/emcip-tdlib-adapter.jar

# Or set it for the Maven run:
export LD_LIBRARY_PATH=/path/to/td/build
mvn spring-boot:run
```

**Verify TDLib is loaded:** Once the application starts, check:
```bash
curl http://localhost:9080/actuator/health
# "tdlib": {"status": "UP"} means the native library loaded correctly
# "tdlib": {"status": "DOWN"} means the library failed to load (check logs)
```

### "Authorization state: WaitPhoneNumber"

The phone number wasn't provided. Either:
- Set `TELEGRAM_PHONE_NUMBER` env var before starting
- Use the REST API to submit: `POST /api/auth/phone`

### "Failed to check code: 400 - CODE_INVALID"

The authentication code expired or was entered incorrectly. Request a new code by restarting the login flow.

### "Kafka connection failed"

Ensure Kafka is running:
```bash
docker-compose ps kafka
docker-compose logs kafka
```

### Session Database Locked

If you get database errors, clear the TDLib database:
```bash
rm -rf tdlib-db/
rm -rf tdlib-files/
```

Then restart the adapter and re-authenticate.

## Security Notes

- **Never commit API credentials** to version control
- **Use environment variables** or external secret management
- **Protect session files** (tdlib-db/) as they contain your Telegram session
- **Enable 2FA** on your Telegram account for better security
- **Rotate credentials** if compromised

## Event Schema

Messages are published to `telegram.raw.messages` topic as JSON:

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "telegramMessageId": 123456789,
  "chatId": -1001234567890,
  "senderId": "987654321",
  "senderType": "USER",
  "text": "Hello, world!",
  "date": 1704067200,
  "editDate": 0,
  "isOutgoing": false,
  "replyToMessageId": 0,
  "replyInChatId": 0,
  "metadata": {
    "textLength": 13,
    "entityCount": 0,
    "messageThreadId": 0,
    "isChannelPost": false,
    "isTopicMessage": false
  },
  "ingestedAt": "2024-01-01T00:00:00Z"
}
```

## API Reference

### Authentication Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/auth/status` | Check auth status |
| POST | `/api/auth/phone` | Submit phone number |
| POST | `/api/auth/code` | Submit auth code |
| POST | `/api/auth/password` | Submit 2FA password |
| POST | `/api/auth/logout` | Logout |

## Docker Deployment

Docker is the recommended deployment path. The multi-stage `Dockerfile` builds TDLib from source,
installs the native library, and packages the Spring Boot app — no host-side setup needed.

```bash
# Start via docker-compose (uses the telegram profile):
docker compose --profile telegram up -d tdlib-adapter

# Monitor startup (first build takes 15-30 min):
docker compose logs -f tdlib-adapter

# Once running, check health:
curl http://localhost:9080/actuator/health
```

**Session persistence:** TDLib session data is stored in Docker volumes `tdlib-data` and
`tdlib-files`. These persist across container restarts. To reset (forces re-authentication):
```bash
docker compose down -v  # WARNING: deletes all volumes including postgres data
# Or target only tdlib volumes:
docker volume rm ecip_tdlib-data ecip_tdlib-files
```

## Further Reading

- [TDLib Documentation](https://core.telegram.org/tdlib/docs/)
- [Telegram API](https://core.telegram.org/api)
- [EVENT_SCHEMAS.md](EVENT_SCHEMAS.md) - Event definitions
- [architecture.adoc](documentation/architecture.adoc) - System architecture

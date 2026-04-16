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

## Configuration

### Environment Variables

Set these environment variables before running:

```bash
export TELEGRAM_API_ID=your_api_id_here        # e.g., 12345678
export TELEGRAM_API_HASH=your_api_hash_here    # e.g., abcdef1234567890abcdef1234567890
export TELEGRAM_PHONE_NUMBER=+1234567890      # Your phone number with country code
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092  # Kafka broker address
```

### Optional Configuration

```bash
export TDLIB_DB_DIR=tdlib-db                   # TDLib database directory
export TDLIB_FILES_DIR=tdlib-files             # TDLib files directory
```

## Running Locally

### 1. Start Infrastructure

```bash
docker-compose up -d kafka zookeeper
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
  --bootstrap-server localhost:9092
```

## Troubleshooting

### "TDLib native library not found"

You need to build or download the TDLib native library:

**Option 1: Build from source**
```bash
git clone https://github.com/tdlib/td.git
cd td
mkdir build && cd build
cmake ..
cmake --build . --target install
```

**Option 2: Use pre-built**
Check if your package manager has TDLib:
```bash
# Ubuntu/Debian
sudo apt-get install libtdlib-dev

# macOS
brew install tdlib
```

Then set the library path:
```bash
export LD_LIBRARY_PATH=/path/to/tdlib/lib:$LD_LIBRARY_PATH
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

The adapter can run in Docker. Note: TDLib requires native libraries, so the Dockerfile must include them.

See `emcip-tdlib-adapter/Dockerfile` for the multi-stage build that includes TDLib.

## Further Reading

- [TDLib Documentation](https://core.telegram.org/tdlib/docs/)
- [Telegram API](https://core.telegram.org/api)
- [EVENT_SCHEMAS.md](EVENT_SCHEMAS.md) - Event definitions
- [architecture.adoc](documentation/architecture.adoc) - System architecture

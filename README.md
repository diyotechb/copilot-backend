# Live Transcription Spring Boot

Spring Boot WebSocket backend for the Live Transcription client. Provides a `/realtime` WebSocket endpoint that accepts PCM16 audio frames from the browser and proxies them to an upstream ASR (AssemblyAI adapter included).

Prerequisites
- Java 17+
- Maven

Quick local run
```bash
cd LiveTranscriptionSpringServer
# Build
mvn clean package

# Run (example) — set your AssemblyAI API key via env
ASSEMBLY_AI_API_KEY=your_key_here java -jar target/live-transcription-0.0.1-SNAPSHOT.jar
```

By default the server binds to port 3001. You can override with `PORT` env var:
```bash
PORT=8080 java -jar target/live-transcription-0.0.1-SNAPSHOT.jar
```

Development (dev server)
```bash
cd LiveTranscriptionSpringServer
mvn spring-boot:run
```

WebSocket endpoint
- `ws://<host>:<port>/realtime` — accepts binary PCM16 frames. The server sends the following JSON message types to clients:
  - `open` — connection acknowledged
  - `proxy_open` — upstream ASR ready; `params` contains server-driven ASR settings (`sample_rate`, `min_silence_threshold`, `max_silence_threshold`, `end_of_turn_threshold`)
  - `message` — normalized transcription messages with `data` containing `{ text, end_of_turn }`
  - `proxy_error` / `closed`

Configuration / Environment Variables
Set these in your environment (or via your platform's environment settings):
- `ASSEMBLY_AI_API_KEY` (required) — AssemblyAI API key used by the adapter.
- `ASSEMBLY_AI_WS_URL` (optional) — AssemblyAI websocket URL (default: `wss://streaming.assemblyai.com/v3/ws`).
- `ASSEMBLY_AI_SAMPLE_RATE` (optional) — Sample rate in Hz (default: `48000`).
- `MIN_SILENCE_THRESHOLD` (optional) — Minimum silence in ms used by server policy (default: `500`).
- `MAX_SILENCE_THRESHOLD` (optional) — Maximum silence in ms used by server policy (default: `2000`).
- `END_OF_TURN_THRESHOLD` (optional) — Confidence threshold (0..1) for end-of-turn (default: `0.3`).
- `PORT` (optional) — Server port (default: `3001`).

Actuator / Monitoring
- Actuator endpoints are enabled (minimal): `/actuator/health`, `/actuator/info`, and `/actuator/prometheus` (Prometheus metrics exposed).

Building an executable JAR
```bash
mvn clean package
# jar will be at target/live-transcription-0.0.1-SNAPSHOT.jar
```

Deploying to AWS Elastic Beanstalk (jar-based)
1. Ensure `eb` CLI is installed and configured with your AWS account.
2. From the project root initialize EB (one-time):
	```bash
	eb init -p java live-transcription --region us-east-1
	```
3. Create an environment (one-time):
	```bash
	eb create live-transcription-env
	```
4. Configure environment variables in the Beanstalk console or with: `eb setenv ASSEMBLY_AI_API_KEY=... PORT=3001` (and other vars listed above).
5. Deploy:
	```bash
	eb deploy
	```

Notes for Beanstalk
- Beanstalk will provide a port through `PORT` — the application reads `server.port=${PORT:3001}`.
- Set `ASSEMBLY_AI_API_KEY` in the environment — do NOT commit keys to source control.
- Use the health endpoint `http://<env>.elasticbeanstalk.com/actuator/health` to verify the app is running.

Security & Production recommendations
- Move secrets to a secure store (AWS Secrets Manager / Parameter Store) instead of plain environment variables for production.
- Set `logging.level.com.example=INFO` or higher in production to reduce noise.
- Add monitoring + alerting for upstream failures and connection leaks.

Troubleshooting
- If WebSocket handshake fails, confirm the client URL uses `ws://` or `wss://` matching server TLS termination.
- Check `server.log` for adapter-level logs. Actuator health and Prometheus endpoints help verify runtime.

Questions or next steps
- I can add an `.ebextensions` config or `Procfile` with JVM tuning, or a CI workflow to build and deploy. Want me to add that?


# EventQR

EventQR is a QR-based event management platform built for attendee registration, event check-in, organizer coordination, staff scanning, reward tracking, and administrative oversight. The repository contains a Java Spring Boot backend, an Android mobile client, and a small read-only log viewer web app that work together to support event operations across multiple user roles.

## Project Status

This is an active project repository for a campus/community event system. It is intended for local development, demonstration, and further feature expansion, not as a production-ready deployment package without environment-specific hardening and configuration.

## Core Idea

The platform allows:

- attendees to discover events, register, view QR credentials, and track rewards
- staff to validate attendee QR codes and perform event scans for entry, exit, attendance, booth visits, benefit claims, and reward redemption
- organizers to manage events, staff assignments, scan purposes, rewards, point rules, reports, and attendee details
- admins to oversee users, accounts, event requests, dashboard stats, and audit activity, with a super admin tier above them

## Tech Stack

### Backend

- Java 21
- Spring Boot 3.5.16, Maven
- PostgreSQL (Supabase) with Flyway migrations
- Spring Security with JWT access and refresh tokens
- Spring Data JPA, Caffeine caching, Actuator health checks
- Brevo transactional email, PDFBox for ID printing, ZXing for QR generation
- Uploads stored in Postgres or an S3-compatible bucket
- Read-only CRUD monitor feed (`/api/v1/monitor`) for the separate log viewer
- Docker, deployed on Render

### Mobile App

- Kotlin 2.2.10 on Android (compileSdk 36, minSdk 26, targetSdk 35), Gradle 9.4.1 with Android Gradle Plugin 9.2.1
- XML layouts with Material Components, using Activity / Contract / Presenter (MVP) for most screens, plus Jetpack Compose (Material 3) for shared components and screen sections
- Retrofit + OkHttp + Gson for backend calls
- ZXing for QR codes, uCrop for image cropping
- Encrypted session storage (AndroidX Security Crypto)
- Splash screen API and a generated Baseline Profile for fast cold start

## Repository Structure

```txt
EventQR/
├── .github/workflows/           # CI and Supabase keepalive
├── EventQRBackend/eventqr/      # Spring Boot backend
│   ├── src/main/java/com/thedavelopers/eventqr/
│   │   ├── features/            # admin, attendance, auditlogs, auth, dashboard,
│   │   │                        # eventrequests, events, idprinting, monitor,
│   │   │                        # notifications, organizer, qrcredentials, qremail,
│   │   │                        # registrations, reports, rewards, scanning, staff,
│   │   │                        # transactions, uploads, users
│   │   └── shared/              # config, constants, exceptions, health, interfaces,
│   │                            # persistence, response, security, utils
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   ├── application-prod.properties
│   │   ├── logback-spring.xml
│   │   └── db/migration/        # Flyway migrations (versioned + repeatable)
│   ├── src/test/                # Backend tests
│   ├── .env.example             # All supported environment variables
│   ├── Dockerfile
│   └── pom.xml
├── EventQRMobile/               # Kotlin Android client
│   ├── app/src/main/java/com/thedavelopers/eventqr/
│   │   ├── core/                # api (Retrofit), cache, format, navigation, session, util
│   │   ├── features/            # admin, attendee, audit, auth, common, dashboard,
│   │   │                        # events, idprinting, landing, notifications,
│   │   │                        # organizer, qrcredential, registrations, reports,
│   │   │                        # rewards, scanpurposes, staff, terms, transactions,
│   │   │                        # uploads, users
│   │   └── ui/                  # shared components, icons and theme
│   ├── app/src/test, androidTest, benchmark
│   ├── baselineprofile/         # Baseline Profile generator module
│   ├── tools/                   # token/icon checks and the connected-test wrapper
│   └── build.gradle.kts
├── EventQRLogViewer/            # read-only CRUD log viewer (Vite + React) for /api/v1/monitor
├── .gitignore
└── README.md
```

## Main Functional Areas

### Attendee features

- discover upcoming events on the home dashboard, with a date filter on the event list
- browse and view events
- register for events and request a new event
- receive QR credentials and keep them in a My passes tab
- view attendance and registration status
- view dashboard stats, transactions and event participation history, with an event picker on the history screen
- claim and track rewards, including redemption status
- view shared notifications with unread state, and profile data
- create an account (with an in-app Terms & Conditions page), change password, and recover a forgotten password with an emailed code

### Staff features

- view a dashboard with today's scan stats and assigned events
- select assigned events
- validate attendee QR codes
- perform event scans by purpose, including booth visits when an event defines more than one booth purpose
- record attendance, exits, claims, and reward redemptions
- review scan history and transaction details
- access event attendee summaries for assigned events
- print attendee IDs when the assignment grants the Print IDs permission
- view notifications

### Organizer features

- view a dashboard with event stats and the closest upcoming event
- request and manage events
- review and search attendee registrations and attendee details
- create and manage staff assignments and their permissions
- configure named scan purposes, including multiple booth visit purposes
- manage rewards, point rules, and event-specific logic, and process reward redemptions
- review transaction logs, event reports, attendance summaries, and audit activity
- configure the ID card template
- view notifications

### Admin features

- view a dashboard with platform stats
- manage users and accounts, including changing roles and disabling, enabling, or deleting accounts
- review event creation requests
- oversee audit logs and platform activity
- with the super admin role, see every account, create admin accounts, and manage admin accounts

## Backend Architecture

The backend is organized as a feature-based Spring application under `com.thedavelopers.eventqr.features`. It includes controllers for auth, users, dashboard, events, event requests, registrations, QR credentials and QR email, scanning, staff, organizer tools, rewards, reward redemptions, transactions, reports, audit logs, ID printing, notifications, uploads, monitor, and admin operations.

The project uses:

- REST controllers under `/api/v1/...`
- Spring Security with JWT access and refresh tokens
- rotating refresh tokens with reuse detection and a 90-day absolute session lifetime
- Flyway migration scripts for schema evolution
- PostgreSQL-backed persistence
- role-aware access across attendee, staff, organizer, admin, and super admin flows
- shared notifications with unread state for scan, registration, reward, points, staff assignment, and event decision events
- transactional check-in handling to keep concurrent scans consistent

Examples of implemented API groups include:

- `/api/v1/auth` (login, refresh, logout, register, change password, and the forgot / verify / reset password code flow)
- `/api/v1/dashboard`
- `/api/v1/events`
- `/api/v1/registrations`
- `/api/v1/staff`
- `/api/v1/organizer`
- `/api/v1/admin`
- `/api/v1/rewards`
- `/api/v1/reports`
- `/api/v1/notifications`
- `/api/v1/monitor` (token-protected, read-only CRUD activity feed for the log viewer)
- `/api/v1/health`

## Mobile App Architecture

The Android application is organized into feature packages on top of a shared `core` layer (API client, snapshots and cache, navigation, session) and a `ui` layer (components, icons, theme). Most screens are XML-layout Activities with a Contract and Presenter; shared components inside those layouts use Jetpack Compose. The app includes:

- landing, login, registration (with a Terms & Conditions page), password reset, and profile screens
- role-based dashboards for attendees, staff, organizers, and admins, each with summary stats
- an attendee home discover list and a My passes tab
- event browsing, registration, and QR credential display
- attendee rewards with redemption status, transaction history with an event picker, and notifications
- one shared notification screen for every role, with unread state and links to the related event or event request
- staff scanning workflows (camera permission is requested only when the scanner opens), assigned events, and transaction logs
- organizer and admin screens for events, scan purposes, reports, ID template configuration, users, and audit logs
- last-known snapshots for the main screens, painted before the network response
- encrypted session storage with background token refresh

### Password recovery

Forgotten passwords are reset entirely inside the app with a one-time code, with no email link:

1. The user enters their email on Forgot Password and taps Send Code. `POST /api/v1/auth/forgot-password` emails a 6-digit code that expires after 15 minutes.
2. On the code screen the user enters the code. `POST /api/v1/auth/reset-password/verify` checks it without using it up.
3. Once verified, the user chooses a new password. `POST /api/v1/auth/reset-password` takes the email, code and new password, then revokes the account's refresh tokens.

Codes are stored only as a hash and lock after 5 wrong attempts. The endpoints are rate limited per IP and per email, resending is limited to one code per 30 seconds and 5 per hour per email, and the app adds an escalating resend cooldown (30s up to 8 minutes, at most 5 resends per visit to the screen). The limits have built-in defaults and can be overridden with `app.forgot-password-rate-limit.*`, `app.reset-verify-rate-limit.*` and `app.reset-password-rate-limit.*`.

Changing a password is separate from recovery: the profile screen sends the current password with the new one, the backend rejects a new password that equals the current one, and a successful change revokes every session before issuing a fresh token pair to the calling device.

The backend base URL comes from the `EVENTQR_BASE_URL` Gradle property and falls back to the deployed backend. To point the app at a local backend, set it in `EventQRMobile/gradle.properties` or on the command line:

```bash
./gradlew assembleDebug -PEVENTQR_BASE_URL=http://10.0.2.2:10000/api/v1/
```

## Local Development Setup

### Prerequisites

- JDK 21
- Maven or Maven wrapper
- PostgreSQL database
- Android Studio
- Android SDK 36 (the app compiles against SDK 36)

### Backend

From the project root:

```bash
cd EventQRBackend/eventqr
./mvnw spring-boot:run
```

On Windows:

```bat
cd EventQRBackend\eventqr
mvnw.cmd spring-boot:run
```

Run the tests with `./mvnw verify`. The OWASP dependency-check plugin is in `pom.xml` but is not part of that build or of CI; run it by hand with `./mvnw org.owasp:dependency-check-maven:check` and set `NVD_API_KEY` to avoid NVD rate limits.

Startup needs `JWT_SECRET`, `BREVO_API_KEY`, and `BREVO_SENDER_EMAIL` exported in the environment that runs Maven, because they have no defaults. `DB_URL` (and its username/password) selects the database and falls back to a local `eventqr` database on `localhost:5432`.

### Docker (optional)

```bash
cd EventQRBackend/eventqr
docker build -t eventqr-backend .
docker run --rm -p 10000:10000 eventqr-backend
```

### Android app

```bash
cd EventQRMobile
./gradlew assembleDebug
```

On Windows:

```bat
cd EventQRMobile
gradlew.bat assembleDebug
```

Then open `EventQRMobile` in Android Studio and run it on an emulator or physical device. Unit tests: `./gradlew testDebugUnitTest`.

## Required Configuration

The backend currently reads its configuration from environment variables (see `.env.example` for the full list) with defaults in `application.properties` and `application-prod.properties`.

Typical configuration includes:

- database host, port, and database name
- database username and password
- JWT secret and token lifetimes
- Brevo API key and sender address
- file storage type (`db` or `s3`) and bucket settings
- backend server port
- mobile app API base URL
- log viewer bearer token, allowed browser origin, and the public-mode toggle

Example environment variables used by the backend:

```bash
DB_URL=jdbc:postgresql://localhost:5432/eventqr
DB_USERNAME=postgres
DB_PASSWORD=postgres
JWT_SECRET=your_jwt_secret_here
JWT_EXPIRATION_MS=3600000
BREVO_API_KEY=your_brevo_key
BREVO_SENDER_EMAIL=noreply@example.com
PORT=10000
```

Do not commit real secrets, production URLs, or private deployment values to version control.

## Database migrations (Flyway)

- Migrations live in `EventQRBackend/eventqr/src/main/resources/db/migration`: versioned `V<n>__*.sql` scripts applied in order, plus repeatable `R__*.sql` reconciliations that run after them. Do not restate the version range here — it changes with every migration. On a fresh database `FreshDatabaseBootstrapCallback` applies the V16 baseline before Flyway runs V1 onwards, because the early migrations only patch tables that baseline creates; Hibernate's `ddl-auto=validate` then only verifies the entity mapping against the migrated schema.
- Never change `spring.jpa.hibernate.ddl-auto` back to `update` or `create` on any environment that Flyway has already migrated — the two approaches fight over schema ownership and Flyway checksums drift.
- `spring.flyway.baseline-on-migrate=true` is only meant for pre-existing non-Flyway databases; a clean deploy does not rely on it.
- Never edit an already-applied guarded migration (V17/V18 guards are historical). When a conditional guard makes a fresh build diverge from production, reconcile the fresh build *down* to production with a **repeatable** migration (`R__...sql`) — never a versioned one during a multi-PR window, where a new version number becomes a "resolved migration not applied to database" failure on the next deploy. Keep the must-not-exist catalogue assertions in `RewardRedemptionPostgresTest` in sync.

## Deployment & operations

The backend runs on Render (Docker, `EventQRBackend/eventqr/Dockerfile`) against a Supabase Postgres database.

### Render environment

- Every variable the backend reads is listed with a placeholder in `EventQRBackend/eventqr/.env.example`. Copy it to `.env` for local use and export the values: Spring Boot does not read `.env` by itself. `.env` is git-ignored.
- `SPRING_PROFILES_ACTIVE=prod` is required in production (small connection pool, graceful shutdown, bounded platform-thread request pool).
- `DB_URL` must be the Supabase **session pooler** URL with SSL, e.g. `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`, with `DB_USERNAME=postgres.<project_ref>`.
- JVM memory flags come from `JAVA_TOOL_OPTIONS` in the Dockerfile. Setting that variable on Render **replaces** the Dockerfile defaults rather than adding to them, so any override must repeat the sizing, GC-logging and `ActiveProcessorCount` flags it wants to keep.
- Set Render's **Health Check Path** to `/actuator/health/readiness`. It is pinned to `readinessState` (in-process only) so the fixed 5 s probe timeout cannot be spent on a database round trip — a DB-backed probe burns the full Hikari connection timeout (5 s) whenever the pool is busy or Postgres is unreachable, and a missed probe restarts the instance. The database check stays on `/api/v1/health` and `/actuator/health` for humans.

### GitHub secrets

| Secret | Used by |
|---|---|
| `SUPABASE_ANON_KEY` | `supabase-keepalive.yml` (keeps the free-tier project from pausing) |
| `SUPABASE_KEY` | fallback name for the same keepalive key |

OWASP dependency-check is not automated in CI. The plugin remains in `pom.xml` and runs by hand with `./mvnw org.owasp:dependency-check-maven:check`.

### Rollback

- Application: in the Render dashboard, open the service's **Events** and use **Rollback** on the last good deploy.
- Schema: Flyway migrations are forward-only. A rolled-back app still sees the newer schema, so undo a schema change with a new migration (`V<next>__revert_<change>.sql`) rather than editing or deleting an applied one.

## Security Notes

This repository should not expose:

- database credentials
- production hostnames or environment values
- JWT secrets
- API tokens
- private service credentials
- local keystore files
- real user or attendee data

## Development Guidelines

- keep backend logic on the backend
- keep API contracts consistent between the Android app and the Spring service layer
- prefer feature-based organization for new work
- validate changes with the relevant backend build/test steps before merging
- CI runs the backend `mvn verify` plus the Android unit tests, release lint, and release build on every pull request
- keep environment-specific configuration out of source control

## License

No license has been specified for this repository yet. Add a license file before distributing or reusing the code publicly.

## Notes

This repository represents a full-stack event management system with QR-driven validation, role-based workflows, and both organizer/admin supervision and attendee-facing interactions. The project is structured for iterative development and is best approached as a connected backend + mobile application rather than as individual isolated modules.

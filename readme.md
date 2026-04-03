# SoftControl AI — Backend & Project Documentation

> **Digital Addiction Control System** — AI-powered smartphone behavior analysis with on-device ML, reinforcement learning, LSTM sequence prediction, and personalized user models.

---

## Table of Contents

- [Project Overview](#project-overview)
- [System Architecture](#system-architecture)
- [Technical Enhancements](#technical-enhancements)
- [Features](#features)
- [Backend Setup](#backend-setup)
- [Project File Structure](#project-file-structure)
- [ML Models](#ml-models)
- [API Reference](#api-reference)
- [Android App](#android-app)
- [Data Pipeline](#data-pipeline)
- [Configuration](#configuration)

---

## Project Overview

SoftControl AI is a student focus and productivity system that:

- Tracks smartphone usage behavior using Android's UsageStats API
- Classifies user behavior as **Focused / Distracted / Addicted** using 6 ML models
- Predicts relapse risk in real-time using XGBoost
- Runs a 25-minute Pomodoro focus timer with automatic violation detection
- Provides personalized AI coaching, gamification, leaderboards, and analytics
- Adapts to individual users through personalized RandomForest models
- Proactively prevents distractions using LSTM + RL-based intervention engine

---

## System Architecture

```
┌─────────────────────────────────────────┐
│          Android App (Kotlin)           │
│                                         │
│  MainActivity  ── FocusActivity         │
│  CoachActivity ── ReportActivity        │
│  GamificationActivity                   │
│  LeaderboardActivity                    │
│  AnalyticsDashboardActivity             │
│                                         │
│  TrackingService (every 15 min)         │
│  InterventionCheck (every 5 min)        │
│  OnDeviceMLHelper (TFLite fallback)     │
└──────────────────┬──────────────────────┘
                   │ HTTP REST (Retrofit)
                   ▼
┌─────────────────────────────────────────┐
│       Flask Backend (Python)            │
│       app.py — Port 5000                │
│                                         │
│  /analyze           /check_intervention │
│  /leaderboard       /missions/<user>    │
│  /analytics/<user>  /log_usage          │
│  /rl_action         /rl_feedback        │
│  /retrain_lstm      /usage_trends       │
└──────────────────┬──────────────────────┘
                   │
        ┌──────────┴──────────┐
        ▼                     ▼
┌───────────────┐    ┌────────────────────┐
│  SQLite DB    │    │  6 ML Models       │
│  softcontrol  │    │                    │
│  .db          │    │  RandomForest      │
│               │    │  XGBoost           │
│  user_sessions│    │  KMeans            │
│  user_profiles│    │  IsolationForest   │
│  leaderboard  │    │  VotingEnsemble    │
│  rl_feedback  │    │  SHAP Explainer    │
│  usage_logs   │    │  LSTM (TensorFlow) │
│  user_missions│    │  Q-Table (RL)      │
└───────────────┘    └────────────────────┘
```

---

## Technical Enhancements

### TE-1 — On-Device ML (TFLite)

The RandomForest model is exported to TensorFlow Lite via a surrogate neural network (`export_tflite.py`). The Android app loads `model.tflite` from assets and runs inference locally when the Flask server is unreachable.

- File: `export_tflite.py`
- Android: `OnDeviceMLHelper.kt`
- Trigger: Server connection failure → fallback to on-device result

**To generate:**
```bash
python export_tflite.py
# Copy tflite/model.tflite → app/src/main/assets/model.tflite
```

---

### TE-2 — Real Training Data

Every 5 tracking cycles, the Android app submits recent session data to the `/submit_training_data` endpoint. The training pipeline (`train_models.py`) reads real sessions from the SQLite database and augments the synthetic training set before retraining all models.

- Android: `TrackingService.kt` → `submitRecentSessionsAsTrainingData()`
- Backend: `POST /submit_training_data`
- Training: `models/train_models.py` reads `data/softcontrol.db` if ≥20 real sessions exist

---

### TE-3 — Reinforcement Learning Agent

A Q-learning agent (`rl_agent.py`) decides which intervention action to show the user based on their current behavioral state.

**State space:**
| Dimension | Values | Buckets |
|---|---|---|
| Risk score | 0.0 – 1.0 | 10 bins |
| Hour of day | 0 – 23 | 8 groups (hour // 3) |
| Violations | 0 – 5 | 6 bins |
| Streak days | 0 – 63 | 10 groups |

**Actions:**
| ID | Name | Description |
|---|---|---|
| 0 | `none` | No intervention |
| 1 | `show_tip` | "Take a short break" |
| 2 | `send_warning` | "High distraction risk" |
| 3 | `suggest_focus` | "Start a focus session now" |

**Epsilon-greedy exploration:** Epsilon starts at 0.3 and decays by 0.995 per update (min 0.05). The agent becomes more exploitative as it learns.

---

### TE-4 — LSTM for Sequential Patterns

An LSTM model (`lstm_model.py`) predicts the user's next-session risk score based on the last 7 sessions.

**Architecture:**
```
LSTM(64) → Dropout(0.2) → LSTM(32) → Dropout(0.2) → Dense(16, relu) → Dense(1, sigmoid)
```

**Input features per timestep (8):** time_spent, app_switches, violations, hour_of_day, risk_score, focus_completed, battery_level, headphone_connected

**Training trigger:** Auto-trains after 100+ real sessions are collected for a user. Also runs during `train_models.py` if the database has ≥60 sessions.

**Fallback:** If TensorFlow is unavailable or model file is missing, uses weighted average of the last 5 risk scores.

---

## Features

### Feature 0 — Continuous App Usage Tracking

`TrackingService.kt` runs every 15 minutes in the background. It queries Android's `UsageStatsManager` for per-app usage data and sends it to `/log_usage`. This builds a continuous behavioral log in the `usage_logs` database table.

- Android: `UsageStatsHelper.getTopAppsUsage()` — top 15 apps by duration
- Backend: `POST /log_usage` → stores in `usage_logs` table
- Categories: social, entertainment, messaging, gaming, browser, other

---

### Feature 1 — Context-Aware Intelligence

Every analysis request includes 4 additional context fields:

| Field | Source | Values |
|---|---|---|
| `location_type` | User-set preference | home / college / other |
| `day_type` | System calendar | weekday / weekend |
| `battery_level` | BatteryManager API | 0 – 100 |
| `headphone_connected` | AudioManager API | true / false |

These are collected by `ContextCollector.kt` and encoded into the ML feature vector as `location_type_enc`, `day_type_enc`, `battery_level`, `headphone_connected`.

---

### Feature 2 — Personalized User Models

After a user accumulates **50+ sessions**, a personal RandomForest model is trained asynchronously on their historical data and saved to `models/personal/<user_id>_rf.pkl`.

- New user (< 50 sessions) → global model
- Returning user (≥ 50 sessions) → personal model
- Retrain trigger: every 10 sessions after session 50
- Response field: `used_personal_model: true/false`

---

### Feature 3 — Real-Time Intervention Engine

`TrackingService.kt` runs a separate coroutine that checks `/check_intervention` every **5 minutes**.

**Backend logic:**
1. If ≥2 recent sessions exist → run LSTM on the sliding window
2. Boost risk by 1.2× if late night (22:00 – 04:59)
3. Boost risk by 1.15× if violations ≥ 2
4. If `short_term_risk > 0.75` → trigger intervention notification on device

Android pushes a heads-up notification via `CHANNEL_INTERVENTION` (high priority).

---

### Feature 4 — Advanced Gamification System

| Component | Details |
|---|---|
| XP | Earned per session. +50 (perfect), +25 (1 violation), +10 (2 violations), -20 (failed) |
| Streak bonus | +5 XP per streak day, capped at +50 |
| Levels | 10 levels: Beginner → Grandmaster |
| Badges | first_session, zero_hero, week_warrior, month_master, century, comeback |
| Daily missions | 3 random missions per day (reset at midnight) |
| Weekly missions | 2 random missions per week |

Missions are generated by the backend (`/missions/<user_id>`) and progress is auto-updated after each `/analyze` call.

---

### Feature 5 — Social & Competitive Features

- Global leaderboard ranked by focus score (`/leaderboard`)
- Per-user rank with score and weekly XP (`/user_rank/<user_id>`)
- Current user's row highlighted in gold on the leaderboard screen
- Weekly XP accumulates; focus score shows all-time best

---

### Feature 7 — Advanced Analytics Dashboard

The `/analytics/<user_id>` endpoint returns:

| Data | Description |
|---|---|
| 30-day session history | Raw session records |
| Summary stats | Total sessions, avg score, avg risk, total violations, screen time |
| Daily trends | Per-day: total_time, avg_score, violations, session_count |
| Peak distraction hours | Top 5 hours where user is most distracted |

The Android `AnalyticsDashboardActivity` renders:
- Line chart: screen time trend (30 days)
- Line chart: self-control score trend
- Bar chart: peak distraction hours

---

## Backend Setup

### Requirements

```bash
pip install flask flask-cors scikit-learn xgboost joblib numpy pandas shap
# Optional (for LSTM):
pip install tensorflow
```

### Run

```bash
cd backend/backend

# Step 1: Generate synthetic dataset
python data/generate_data.py

# Step 2: Train all models
python models/train_models.py

# Step 3: (Optional) Export TFLite model for on-device ML
python export_tflite.py

# Step 4: Start server
python app.py
```

Server starts at `http://0.0.0.0:5000`

---

## Project File Structure

```
backend/
└── backend/
    ├── app.py                      # Main Flask API server
    ├── user_db.py                  # SQLite database + all DB functions
    ├── lstm_model.py               # LSTM sequential predictor (TE-4)
    ├── rl_agent.py                 # Q-learning RL agent (TE-3)
    ├── personal_model_trainer.py   # Per-user RandomForest (Feature 2)
    ├── export_tflite.py            # RF → TFLite conversion (TE-1)
    ├── README.md                   # This file
    ├── data/
    │   ├── generate_data.py        # Synthetic dataset generator (3000 samples)
    │   ├── behavior_data_v3.csv    # Generated training dataset
    │   └── softcontrol.db          # SQLite database (auto-created)
    └── models/
        ├── train_models.py         # Full training pipeline (TE-2 augmentation)
        ├── rf_classifier.pkl       # RandomForest — primary classifier
        ├── xgb_relapse.pkl         # XGBoost — relapse risk predictor
        ├── kmeans.pkl              # KMeans — user clustering (4 clusters)
        ├── isolation_forest.pkl    # IsolationForest — binge detection
        ├── ensemble.pkl            # VotingClassifier — meta ensemble
        ├── label_encoder.pkl       # LabelEncoder for class names
        ├── scaler.pkl              # StandardScaler for ensemble/kmeans
        ├── shap_explainer.pkl      # SHAP TreeExplainer
        ├── linear_regression.pkl   # Linear regression — score forecast
        ├── lstm_model.h5           # LSTM model (TensorFlow, auto-trained)
        ├── q_table.npy             # RL Q-table
        ├── rl_epsilon.json         # Current RL exploration rate
        ├── model_version.txt       # Version marker
        └── personal/
            └── <user_id>_rf.pkl    # Per-user personalized models

app/
└── app/src/main/java/com/softcontrol/ai/
    ├── MainActivity.kt             # Dashboard
    ├── FocusActivity.kt            # 25-min Pomodoro timer
    ├── CoachActivity.kt            # AI chatbot
    ├── ReportActivity.kt           # Session history timeline
    ├── GamificationActivity.kt     # XP / missions / badges  [NEW]
    ├── LeaderboardActivity.kt      # Social leaderboard       [NEW]
    ├── AnalyticsDashboardActivity.kt # Charts + trends        [NEW]
    ├── TrackingService.kt          # Background monitoring
    ├── ApiService.kt               # Retrofit HTTP client
    ├── NotificationHelper.kt       # All notification channels
    ├── UsageStatsHelper.kt         # Android UsageStats API
    ├── ContextCollector.kt         # Battery / headphone / location [NEW]
    ├── OnDeviceMLHelper.kt         # TFLite on-device inference     [NEW]
    ├── GamificationManager.kt      # XP / level / badge logic
    ├── UserProfileManager.kt       # User ID management
    ├── RLManager.kt                # RL feedback loop
    ├── SimulationHelper.kt         # Demo data preloader
    └── BootReceiver.kt             # Auto-restart after reboot
```

---

## ML Models

| Model | Algorithm | Purpose | Input | Output |
|---|---|---|---|---|
| RandomForest | RF (300 trees) | Primary classifier | 16 features | focused / distracted / addicted |
| XGBoost | GBDT (300 trees) | Relapse risk | 16 features | probability [0, 1] |
| KMeans | K=4 | User clustering | Scaled 16 features | cluster 0–3 |
| IsolationForest | Anomaly detection | Binge session flag | 16 features | -1 (binge) / 1 (normal) |
| VotingEnsemble | RF + LR + SVM | Meta-classifier | Scaled 16 features | focused / distracted / addicted |
| SHAP Explainer | TreeExplainer | Feature importance | RF model | SHAP values per feature |
| LSTM | 2-layer LSTM | Sequential risk | Last 7 sessions × 8 features | risk score [0, 1] |
| Personal RF | RF (100 trees) | Per-user model | User's own 50+ sessions | focused / distracted / addicted |

### 16 Input Features

| # | Feature | Description |
|---|---|---|
| 1 | `time_spent` | Total screen time today (minutes) |
| 2 | `app_switches` | Number of distinct apps used |
| 3 | `hour_of_day` | Current hour (0–23) |
| 4 | `violations` | Focus session breaks |
| 5 | `session_gap` | Minutes since last session |
| 6 | `previous_usage` | Last session screen time |
| 7 | `focus_sessions` | Completed focus sessions today |
| 8 | `day_of_week` | 0 = Monday, 6 = Sunday |
| 9 | `interaction_intensity` | time_spent × app_switches |
| 10 | `discipline_score` | focus_sessions × 10 – violations × 5 |
| 11 | `usage_pressure` | previous_usage / (session_gap + 1) |
| 12 | `night_flag` | 1 if hour ≥ 22 or hour < 5 |
| 13 | `location_type_enc` | home=0, college=1, other=2 |
| 14 | `day_type_enc` | weekday=0, weekend=1 |
| 15 | `battery_level` | Battery percentage (0–100) |
| 16 | `headphone_connected` | 0 or 1 |

### User Cluster Profiles

| Cluster | Name | Pattern |
|---|---|---|
| 0 | Night Owl | High usage 22:00–04:59, low battery at night |
| 1 | Binge User | Very long sessions, high time_spent |
| 2 | Impulsive User | Very high app_switches, short bursts |
| 3 | Regular User | Balanced, daytime usage |

---

## API Reference

### `POST /analyze`

Main endpoint. Classifies behavior and returns full AI analysis.

**Request body:**
```json
{
  "time_spent": 87.5,
  "app_switches": 24,
  "hour_of_day": 22,
  "violations": 2,
  "focus_completed": false,
  "user_id": "uuid-string",
  "display_name": "Player",
  "location_type": "home",
  "day_type": "weekday",
  "battery_level": 35,
  "headphone_connected": false,
  "streak": 3
}
```

**Response:**
```json
{
  "label": "distracted",
  "ensemble_label": "distracted",
  "confidence": 0.82,
  "risk_score": 0.71,
  "self_control_score": 54,
  "monster_level": 3,
  "cluster": "Night Owl",
  "cluster_id": 0,
  "is_binge_session": false,
  "anomaly_score": 0.045,
  "explanations": {"time_spent": 0.32, "violations": 0.28, ...},
  "top_factor": "time_spent",
  "insight": "...",
  "coach_tip": "...",
  "remarks": {"good": [], "bad": ["High distraction level"]},
  "weekly_screen_time_hours": 42.5,
  "xp_earned": 5,
  "lstm_risk": 0.68,
  "lstm_trend": "worsening",
  "rl_action": "send_warning",
  "rl_message": "⚠️ High distraction risk detected.",
  "used_personal_model": false
}
```

---

### `POST /check_intervention`

Real-time intervention check (called every 5 min from device).

```json
{
  "user_id": "uuid",
  "risk_score": 0.65,
  "hour_of_day": 23,
  "violations": 1,
  "streak": 3,
  "recent_sessions": [{"time_spent": 90, "risk_score": 0.6, ...}]
}
```

---

### `GET /missions/<user_id>`

Returns daily and weekly missions for the user. Creates them if they don't exist for today/this week.

---

### `GET /analytics/<user_id>`

Returns 30-day session history, summary stats, daily trends, and peak distraction hours.

---

### `GET /leaderboard?limit=50`

Returns top N users ranked by focus score.

---

### `GET /user_rank/<user_id>`

Returns the user's rank and their leaderboard profile.

---

### `POST /log_usage`

Receives per-app usage data from device.

```json
{
  "user_id": "uuid",
  "app_logs": [
    {"package_name": "com.instagram.android", "app_name": "Instagram",
     "duration_minutes": 34.5, "category": "social"}
  ]
}
```

---

### `POST /rl_feedback`

Sends reward signal back to the RL agent after outcome is observed.

```json
{
  "user_id": "uuid",
  "action": 2,
  "state": {"risk": 0.7, "hour": 22, "violations": 1, "streak": 3},
  "next_state": {"risk": 0.5, "hour": 22, "violations": 1, "streak": 3},
  "reward": 1.0
}
```

---

### `POST /retrain_lstm`

Triggers LSTM retraining on all sessions for a user (async).

```json
{"user_id": "uuid"}
```

---

## Android App

### Requirements

- Android 8.0+ (API 26)
- `PACKAGE_USAGE_STATS` permission (granted via Settings)
- `POST_NOTIFICATIONS` permission (Android 13+)
- WiFi connection to the PC running Flask

### Key Settings

**Change backend IP in `ApiService.kt`:**
```kotlin
private const val BASE_URL = "http://<YOUR_PC_WIFI_IP>:5000/"
```

Find your PC's IP with `ipconfig` (Windows) or `ifconfig` (Mac/Linux). Both devices must be on the same WiFi network.

### On-Device ML Setup

After running `export_tflite.py` on the backend:
```
backend/tflite/model.tflite
        ↓ copy to
app/app/src/main/assets/model.tflite
```

The app will use this as a fallback when the server is unreachable.

### Location Type

Users can set their location type in SharedPreferences. The default is `"other"`. To add a settings screen later, use:
```kotlin
ContextCollector.setLocationType(context, "college")  // "home" | "college" | "other"
```

---

## Data Pipeline

```
1. generate_data.py
   └── Creates behavior_data_v3.csv (3000 synthetic samples, 16 features)

2. train_models.py
   ├── Loads behavior_data_v3.csv
   ├── Merges real sessions from softcontrol.db (if ≥20 exist)
   ├── Trains: RF, XGBoost, KMeans, IsolationForest, Ensemble, SHAP
   ├── Trains: LSTM (if TensorFlow available and ≥60 real sessions)
   └── Saves all models to models/

3. export_tflite.py
   ├── Loads RF model
   ├── Generates 8000 surrogate samples
   ├── Trains 3-layer NN on surrogate data
   ├── Converts to TFLite format
   └── Saves to tflite/model.tflite

4. app.py (runtime)
   ├── Loads all 6 models at startup
   ├── Auto-regenerates models if missing or feature count mismatch
   ├── Accepts real session data from Android app
   └── Triggers personal model training async (after 50+ sessions per user)
```

---

## Configuration

| Setting | File | Default | Description |
|---|---|---|---|
| Backend IP | `ApiService.kt` | `10.255.231.101` | Change to your PC's WiFi IP |
| Backend port | `app.py` | `5000` | Flask server port |
| Training samples | `generate_data.py` | `3000` | Synthetic dataset size |
| Personal model threshold | `personal_model_trainer.py` | `50` | Minimum sessions before personal model |
| LSTM training threshold | `lstm_model.py` | `17` | Minimum sessions (SEQ_LEN + 10) |
| Tracking interval | `TrackingService.kt` | `15 min` | Background analysis frequency |
| Intervention interval | `TrackingService.kt` | `5 min` | Intervention check frequency |
| RL epsilon start | `rl_agent.py` | `0.3` | Initial exploration rate |
| RL epsilon decay | `rl_agent.py` | `0.995` | Per-update decay factor |
| Focus session length | `FocusActivity.kt` | `25 min` | Pomodoro timer duration |
| Session history kept | `FocusActivity.kt` | `14` | Sessions stored locally on device |

---

## Notes

- The backend must run on the same WiFi network as the Android device
- SQLite database is created automatically at `data/softcontrol.db` on first run
- All models are auto-generated on first startup if missing
- LSTM training requires TensorFlow (`pip install tensorflow`) — the system works without it using a weighted-average fallback
- Personal models are stored per-user in `models/personal/` — these are never overwritten by global retraining

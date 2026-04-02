import pandas as pd
import numpy as np
import os

np.random.seed(42)
n = 2000  # Doubled dataset size for better generalization

hours = np.random.randint(0, 24, n)
days = np.random.randint(0, 7, n)

time_spent = []
app_switches = []
violations = []
labels = []
session_gap = []
previous_usage = []
focus_sessions = []
addiction_scores = []
relapse_risk = []

for i, h in enumerate(hours):
    d = days[i]

    # ── Late night / early morning (22:00–04:59) ──────────────────────────
    if 22 <= h or h < 5:
        t    = np.random.randint(120, 400)
        s    = np.random.randint(10, 35)
        v    = np.random.randint(2, 6)
        gap  = np.random.randint(1, 10)
        prev = np.random.randint(150, 400)
        foc  = np.random.randint(0, 2)
        l    = np.random.choice(['distracted', 'addicted'], p=[0.3, 0.7])

    # ── Work hours (09:00–17:00) ──────────────────────────────────────────
    elif 9 <= h <= 17:
        t    = np.random.randint(10, 80)
        s    = np.random.randint(1, 8)
        v    = np.random.randint(0, 2)
        gap  = np.random.randint(20, 60)
        prev = np.random.randint(10, 120)
        foc  = np.random.randint(1, 6)
        l    = np.random.choice(['focused', 'distracted'], p=[0.75, 0.25])

    # ── Evening / morning transition ──────────────────────────────────────
    else:
        t    = np.random.randint(30, 200)
        s    = np.random.randint(3, 20)
        v    = np.random.randint(0, 4)
        gap  = np.random.randint(5, 30)
        prev = np.random.randint(50, 200)
        foc  = np.random.randint(0, 4)
        l    = np.random.choice(['focused', 'distracted', 'addicted'],
                                p=[0.40, 0.35, 0.25])

    # ── Addiction score ───────────────────────────────────────────────────
    score = (t * 0.3 + s * 2.0 + v * 10.0) / 10.0
    score = float(min(100.0, score))

    # ── FIX: Probabilistic relapse risk (avoids 100 % XGBoost accuracy) ──
    if score > 60:
        risk = int(np.random.choice([0, 1], p=[0.30, 0.70]))
    elif score > 40:
        risk = int(np.random.choice([0, 1], p=[0.60, 0.40]))
    else:
        risk = int(np.random.choice([0, 1], p=[0.85, 0.15]))

    time_spent.append(t)
    app_switches.append(s)
    violations.append(v)
    labels.append(l)
    session_gap.append(gap)
    previous_usage.append(prev)
    focus_sessions.append(foc)
    addiction_scores.append(score)
    relapse_risk.append(risk)

df = pd.DataFrame({
    'time_spent':     time_spent,
    'app_switches':   app_switches,
    'hour_of_day':    hours,
    'day_of_week':    days,
    'violations':     violations,
    'session_gap':    session_gap,
    'previous_usage': previous_usage,
    'focus_sessions': focus_sessions,
    'addiction_score': addiction_scores,
    'relapse_risk':   relapse_risk,
    'label':          labels,
})

# ── Derived features (added directly into the dataset) ───────────────────
df['interaction_intensity'] = df['time_spent'] * df['app_switches']
df['discipline_score']      = df['focus_sessions'] * 10 - df['violations'] * 5
df['usage_pressure']        = df['previous_usage'] / (df['session_gap'] + 1)
df['night_flag']            = ((df['hour_of_day'] >= 22) |
                               (df['hour_of_day'] < 5)).astype(int)

os.makedirs('data', exist_ok=True)
df.to_csv('data/behavior_data_v3.csv', index=False)

print("✅  Dataset v3 created!")
print("Shape:", df.shape)
print("\nLabel distribution:")
print(df['label'].value_counts())
print("\nRelapse risk distribution:")
print(df['relapse_risk'].value_counts())
print("\nSample data:")
print(df.head())
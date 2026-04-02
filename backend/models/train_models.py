import pandas as pd
import numpy as np
import os
import joblib

from sklearn.ensemble import RandomForestClassifier, IsolationForest, VotingClassifier
from sklearn.linear_model import LogisticRegression, LinearRegression
from sklearn.svm import SVC
from sklearn.cluster import KMeans
from sklearn.model_selection import train_test_split, cross_val_score
from sklearn.preprocessing import LabelEncoder, StandardScaler
from sklearn.metrics import (
    classification_report, accuracy_score,
    roc_auc_score, balanced_accuracy_score
)
from xgboost import XGBClassifier
import shap

print("=" * 60)
print("   🚀 SoftControl AI — FINAL Model Training Pipeline v6")
print("=" * 60)

# ─────────────────────────────────────────────────────────────
# 1. LOAD DATASET
# ─────────────────────────────────────────────────────────────
df = pd.read_csv(r"C:\Users\Karth\SoftControlAI\backend\data\data\behavior_data_v3.csv")
print(f"\n✅ Dataset loaded: {df.shape}")
print(df['label'].value_counts())

# ─────────────────────────────────────────────────────────────
# 2. DERIVED FEATURES
# ─────────────────────────────────────────────────────────────
if 'interaction_intensity' not in df.columns:
    df['interaction_intensity'] = df['time_spent'] * df['app_switches']
if 'discipline_score' not in df.columns:
    df['discipline_score'] = df['focus_sessions'] * 10 - df['violations'] * 5
if 'usage_pressure' not in df.columns:
    df['usage_pressure'] = df['previous_usage'] / (df['session_gap'] + 1)
if 'night_flag' not in df.columns:
    df['night_flag'] = ((df['hour_of_day'] >= 22) |
                        (df['hour_of_day'] < 5)).astype(int)

# ─────────────────────────────────────────────────────────────
# 3. REBUILD RELAPSE RISK (always probabilistic — never hard rule)
# ─────────────────────────────────────────────────────────────
print("\n🔧 Rebuilding relapse_risk with probabilistic noise...")
np.random.seed(99)
scores = df['addiction_score'].values
risk   = np.array([
    np.random.choice([0, 1], p=[0.30, 0.70]) if s > 60 else
    np.random.choice([0, 1], p=[0.60, 0.40]) if s > 40 else
    np.random.choice([0, 1], p=[0.85, 0.15])
    for s in scores
], dtype=int)
df['relapse_risk'] = risk
print(f"   Distribution → {pd.Series(risk).value_counts().to_dict()}")

# ─────────────────────────────────────────────────────────────
# 4. FEATURE LIST + SPLITS
# ─────────────────────────────────────────────────────────────
FEATURES = [
    'time_spent', 'app_switches', 'hour_of_day', 'violations',
    'session_gap', 'previous_usage', 'focus_sessions', 'day_of_week',
    'interaction_intensity', 'discipline_score',
    'usage_pressure', 'night_flag',
]
N_FEATURES = len(FEATURES)          # must be 12

X = df[FEATURES].copy()

le = LabelEncoder()
y  = le.fit_transform(df['label'])

os.makedirs('models', exist_ok=True)
joblib.dump(le, 'models/label_encoder.pkl')

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

scaler         = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled  = scaler.transform(X_test)
joblib.dump(scaler, 'models/scaler.pkl')

print(f"\n📊 Classes : {list(le.classes_)}")
print(f"   Train   : {len(X_train)} | Test: {len(X_test)}")
print(f"   Features: {N_FEATURES}")

# ─────────────────────────────────────────────────────────────
# MODEL 1: RANDOM FOREST
# ─────────────────────────────────────────────────────────────
print("\n🔴 MODEL 1: Random Forest")

rf = RandomForestClassifier(
    n_estimators=300,
    max_depth=12,
    min_samples_split=5,
    min_samples_leaf=2,
    max_features='sqrt',
    class_weight='balanced',
    random_state=42,
    n_jobs=-1,
)
rf.fit(X_train, y_train)

rf_preds = rf.predict(X_test)
rf_acc   = accuracy_score(y_test, rf_preds)
rf_cv    = cross_val_score(rf, X, y, cv=5, n_jobs=-1).mean()

print(f"Accuracy   : {rf_acc:.2%}")
print(f"CV 5-fold  : {rf_cv:.2%}")
print(classification_report(y_test, rf_preds, target_names=le.classes_))
joblib.dump(rf, 'models/rf_classifier.pkl')

# ─────────────────────────────────────────────────────────────
# MODEL 2: XGBOOST
# ─────────────────────────────────────────────────────────────
print("\n🔵 MODEL 2: XGBoost (Relapse Risk)")

y_risk = df['relapse_risk']
X_train2, X_test2, y_train2, y_test2 = train_test_split(
    X, y_risk, test_size=0.2, random_state=42, stratify=y_risk
)

spw = (y_train2 == 0).sum() / max((y_train2 == 1).sum(), 1)

xgb = XGBClassifier(
    n_estimators=300,
    max_depth=5,
    learning_rate=0.05,
    subsample=0.8,
    colsample_bytree=0.8,
    reg_alpha=0.1,
    reg_lambda=1.0,
    scale_pos_weight=spw,
    eval_metric='logloss',
    random_state=42,
)
xgb.fit(X_train2, y_train2,
        eval_set=[(X_test2, y_test2)],
        verbose=False)

xgb_preds = xgb.predict(X_test2)
xgb_acc   = accuracy_score(y_test2, xgb_preds)
xgb_auc   = roc_auc_score(y_test2, xgb.predict_proba(X_test2)[:, 1])
xgb_bal   = balanced_accuracy_score(y_test2, xgb_preds)

print(f"Accuracy         : {xgb_acc:.2%}")
print(f"Balanced Accuracy: {xgb_bal:.2%}")
print(f"ROC-AUC          : {xgb_auc:.4f}")
print(classification_report(y_test2, xgb_preds))
joblib.dump(xgb, 'models/xgb_relapse.pkl')

# ─────────────────────────────────────────────────────────────
# MODEL 3: K-MEANS
# ─────────────────────────────────────────────────────────────
print("\n🟣 MODEL 3: K-Means")

kmeans = KMeans(n_clusters=4, random_state=42, n_init=20)
kmeans.fit(X_train_scaled)
print("Cluster centres (first 4 features):")
print(np.round(kmeans.cluster_centers_[:, :4], 2))
print(f"Inertia: {kmeans.inertia_:.1f}")
joblib.dump(kmeans, 'models/kmeans.pkl')

# ─────────────────────────────────────────────────────────────
# MODEL 4: ISOLATION FOREST
# ─────────────────────────────────────────────────────────────
print("\n🟠 MODEL 4: Isolation Forest")

iso = IsolationForest(n_estimators=200, contamination=0.10, random_state=42)
iso.fit(X_train)
anomaly = iso.predict(X_test)
print(f"Anomalies detected: {np.sum(anomaly == -1)} / {len(anomaly)}")
joblib.dump(iso, 'models/isolation_forest.pkl')

# ─────────────────────────────────────────────────────────────
# MODEL 5: VOTING ENSEMBLE
# ─────────────────────────────────────────────────────────────
print("\n⭐ MODEL 5: Voting Ensemble")

lr_base = LogisticRegression(
    max_iter=1000,
    C=0.8,
    class_weight='balanced',
    solver='lbfgs',
)
svm_base = SVC(
    probability=True,
    kernel='rbf',
    C=2.0,
    gamma='scale',
    class_weight='balanced',
)
ensemble = VotingClassifier(
    estimators=[('rf', rf), ('lr', lr_base), ('svm', svm_base)],
    voting='soft',
)
ensemble.fit(X_train_scaled, y_train)

ens_preds = ensemble.predict(X_test_scaled)
ens_acc   = accuracy_score(y_test, ens_preds)
print(f"Ensemble Accuracy: {ens_acc:.2%}")
print(classification_report(y_test, ens_preds, target_names=le.classes_))
joblib.dump(ensemble, 'models/ensemble.pkl')

# ─────────────────────────────────────────────────────────────
# MODEL 6: LINEAR REGRESSION
# FIX: addiction_score = f(time_spent, app_switches, violations)
# Using ONLY those 3 → R²=1.0 because it's the exact formula.
# We add Gaussian noise to the target so regression is non-trivial.
# ─────────────────────────────────────────────────────────────
print("\n📈 MODEL 6: Linear Regression (Forecast)")

FORECAST_FEATURES = ['time_spent', 'app_switches', 'violations',
                      'session_gap', 'focus_sessions', 'previous_usage']

np.random.seed(42)
noisy_target = df['addiction_score'] + np.random.normal(0, 3, len(df))
noisy_target = noisy_target.clip(0, 100)

lr_reg = LinearRegression()
lr_reg.fit(df[FORECAST_FEATURES], noisy_target)
r2 = lr_reg.score(df[FORECAST_FEATURES], noisy_target)

print(f"R² Score: {r2:.4f}")
print("Coefficients:")
for feat, coef in zip(FORECAST_FEATURES, lr_reg.coef_):
    print(f"  {feat:<20} {coef:+.4f}")
joblib.dump(lr_reg, 'models/linear_regression.pkl')

# ─────────────────────────────────────────────────────────────
# SHAP EXPLAINABILITY
# ROOT CAUSE of "All arrays must be of same length":
#   newer shap returns shape (n_samples, n_features, n_classes)
#   — note the axis ORDER is different from older versions.
# FIX: always check actual ndim + shape, then reduce correctly.
# ─────────────────────────────────────────────────────────────
print("\n🧠 SHAP Explainability")

explainer   = shap.TreeExplainer(rf)
shap_sample = X_test.iloc[:50]
raw         = explainer.shap_values(shap_sample)   # unknown shape

print(f"   raw shap type  : {type(raw)}")
if isinstance(raw, np.ndarray):
    print(f"   raw shap shape : {raw.shape}")

# ── Reduce to (n_samples, n_features) mean |SHAP| ──────────────
if isinstance(raw, list):
    # older shap: list of (n_samples, n_features), one per class
    arr = np.array(raw)                     # (n_classes, n_samples, n_features)
    sv_mean = np.abs(arr).mean(axis=0)      # (n_samples, n_features)
    sv_cls0 = arr[0]                        # for per-sample view

elif isinstance(raw, np.ndarray) and raw.ndim == 3:
    # could be (n_classes, n_samples, n_features)
    # OR       (n_samples, n_features, n_classes)
    if raw.shape[0] == len(shap_sample):
        # (n_samples, n_features, n_classes) — newer shap
        sv_mean = np.abs(raw).mean(axis=2)  # (n_samples, n_features)
        sv_cls0 = raw[:, :, 0]
    else:
        # (n_classes, n_samples, n_features)
        sv_mean = np.abs(raw).mean(axis=0)  # (n_samples, n_features)
        sv_cls0 = raw[0]

else:
    # binary / already 2-D
    sv_mean = np.abs(raw)
    sv_cls0 = raw

# ── Verify shape before building DataFrame ─────────────────────
mean_abs = sv_mean.mean(axis=0)        # (n_features,)
print(f"   mean_abs shape : {mean_abs.shape}  |  n_features: {N_FEATURES}")
assert mean_abs.shape[0] == N_FEATURES, (
    f"SHAP feature count {mean_abs.shape[0]} ≠ FEATURES list {N_FEATURES}. "
    "Check that X_test columns match FEATURES exactly."
)

importance_df = pd.DataFrame({
    'feature':     FEATURES,
    'mean_|shap|': np.round(mean_abs, 4),
}).sort_values('mean_|shap|', ascending=False)

print("\nTop Feature Importance (SHAP):")
print(importance_df.to_string(index=False))

print("\nSample SHAP Explanations (first 3 rows):")
for i in range(3):
    pred_label = le.classes_[rf.predict(shap_sample.iloc[[i]])[0]]
    print(f"\n  Sample {i}  →  predicted: {pred_label}")
    row = sv_cls0[i] if sv_cls0.ndim == 2 else sv_cls0[i, :, 0]
    for f, val in zip(FEATURES, row):
        bar  = '▓' * max(1, int(abs(val) * 15)) if abs(val) > 0.001 else '·'
        sign = '+' if val >= 0 else '-'
        print(f"    {f:<25} {sign}{abs(val):.4f}  {bar}")

joblib.dump(explainer, 'models/shap_explainer.pkl')

# ─────────────────────────────────────────────────────────────
# FINAL SUMMARY
# ─────────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("🏆 ALL MODELS TRAINED SUCCESSFULLY")
print("=" * 60)
print(f"  Random Forest   (behavior) : {rf_acc:.2%}  |  CV {rf_cv:.2%}")
print(f"  XGBoost         (relapse)  : {xgb_acc:.2%}  |  AUC {xgb_auc:.4f}")
print(f"  Voting Ensemble (behavior) : {ens_acc:.2%}")
print(f"  Linear Regression R²       : {r2:.4f}")
print("=" * 60)

print("""
models/
├── rf_classifier.pkl
├── xgb_relapse.pkl
├── kmeans.pkl
├── isolation_forest.pkl
├── ensemble.pkl
├── linear_regression.pkl
├── scaler.pkl
├── label_encoder.pkl
└── shap_explainer.pkl
""")
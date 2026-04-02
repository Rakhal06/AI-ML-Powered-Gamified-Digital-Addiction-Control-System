from flask import Flask, request, jsonify
from flask_cors import CORS
import joblib
import numpy as np
import shap
import datetime
import os

app = Flask(__name__)
CORS(app)

# ── Auto-generate models if missing ───────────────────────
def generate_and_save_models():
    from sklearn.ensemble import RandomForestClassifier, IsolationForest, VotingClassifier
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import LabelEncoder
    from sklearn.linear_model import LogisticRegression
    from sklearn.svm import SVC
    from xgboost import XGBClassifier

    os.makedirs('models', exist_ok=True)
    print("Generating synthetic training data...")

    np.random.seed(42)
    N = 1000

    time_spent   = np.random.randint(5, 300, N).astype(float)
    app_switches = np.random.randint(0, 40, N).astype(float)
    hour_of_day  = np.random.randint(0, 24, N).astype(float)
    violations   = np.random.randint(0, 8, N).astype(float)

    X = np.column_stack([time_spent, app_switches, hour_of_day, violations])

    labels = []
    for i in range(N):
        if time_spent[i] > 150 or violations[i] >= 4:
            labels.append('addicted')
        elif app_switches[i] > 15 or violations[i] >= 2:
            labels.append('distracted')
        else:
            labels.append('focused')
    labels = np.array(labels)

    le = LabelEncoder()
    y  = le.fit_transform(labels)

    rf = RandomForestClassifier(n_estimators=100, random_state=42)
    rf.fit(X, y)
    joblib.dump(rf, 'models/rf_classifier.pkl')
    print("  rf_classifier.pkl saved")

    y_binary = (labels == 'addicted').astype(int)
    xgb = XGBClassifier(n_estimators=100, random_state=42, eval_metric='logloss')
    xgb.fit(X, y_binary)
    joblib.dump(xgb, 'models/xgb_relapse.pkl')
    print("  xgb_relapse.pkl saved")

    kmeans = KMeans(n_clusters=3, random_state=42, n_init=10)
    kmeans.fit(X)
    joblib.dump(kmeans, 'models/kmeans_cluster.pkl')
    print("  kmeans_cluster.pkl saved")

    iso = IsolationForest(contamination=0.1, random_state=42)
    iso.fit(X)
    joblib.dump(iso, 'models/isolation_forest.pkl')
    print("  isolation_forest.pkl saved")

    lr  = LogisticRegression(max_iter=1000, random_state=42)
    svc = SVC(probability=True, random_state=42)
    ensemble = VotingClassifier(
        estimators=[('rf', rf), ('lr', lr), ('svc', svc)],
        voting='soft'
    )
    ensemble.fit(X, y)
    joblib.dump(ensemble, 'models/voting_ensemble.pkl')
    print("  voting_ensemble.pkl saved")

    joblib.dump(le, 'models/label_encoder.pkl')
    print("  label_encoder.pkl saved")
    print("All models generated and saved!\n")


MODEL_FILES = [
    'models/rf_classifier.pkl',
    'models/xgb_relapse.pkl',
    'models/kmeans_cluster.pkl',
    'models/isolation_forest.pkl',
    'models/voting_ensemble.pkl',
    'models/label_encoder.pkl',
]

if not all(os.path.exists(f) for f in MODEL_FILES):
    print("One or more model files missing. Auto-generating...")
    generate_and_save_models()

print("Loading models...")
rf       = joblib.load('models/rf_classifier.pkl')
xgb      = joblib.load('models/xgb_relapse.pkl')
kmeans   = joblib.load('models/kmeans_cluster.pkl')
iso      = joblib.load('models/isolation_forest.pkl')
ensemble = joblib.load('models/voting_ensemble.pkl')
le       = joblib.load('models/label_encoder.pkl')
print("All models loaded!")

explainer = shap.TreeExplainer(rf)
print("SHAP explainer ready!")

FEATURES = ['time_spent', 'app_switches', 'hour_of_day', 'violations']
CLUSTER_NAMES = {0: "Night Owl", 1: "Binge User", 2: "Impulsive User"}


def extract_shap_values(shap_vals, label_idx, n_features):
    """Version-safe SHAP extraction for old and new SHAP APIs."""
    if isinstance(shap_vals, list):
        # Old SHAP: list of (n_samples, n_features) arrays per class
        return np.array(shap_vals[label_idx]).flatten()[:n_features]
    arr = np.array(shap_vals)
    if arr.ndim == 3:
        # New SHAP: (n_samples, n_features, n_classes)
        return arr[0, :, label_idx]
    if arr.ndim == 2:
        return arr[0]
    return arr.flatten()[:n_features]


def get_insight(label, risk, cluster, hour, violations, is_binge):
    parts = []
    if label == 'addicted':
        parts.append("You are showing addictive behavior patterns.")
    elif label == 'distracted':
        parts.append("You are getting distracted. Try to reduce app switching.")
    else:
        parts.append("Great job! You are staying focused.")

    if risk > 0.7:
        parts.append(f"Your relapse risk is HIGH ({int(risk*100)}%). Take a break now.")
    elif risk > 0.4:
        parts.append(f"Relapse risk is MODERATE ({int(risk*100)}%). Stay mindful.")

    if is_binge:
        parts.append("This session is flagged as a binge session by anomaly detection.")
    if 22 <= hour or hour < 5:
        parts.append("You tend to lose focus late at night.")

    parts.append(f"Your usage profile: {cluster}.")
    return " ".join(parts)


def get_coach_tip(label, violations, risk):
    if violations >= 3:
        return "You broke focus 3+ times. Try a 10-minute digital detox before your next session."
    elif label == 'addicted' and risk > 0.6:
        return "High addiction risk detected. Enable strict mode and set a 20-minute session limit."
    elif label == 'distracted':
        return "You keep switching apps. Try the Pomodoro method: 25 minutes focus, 5 minutes break."
    elif label == 'focused':
        return "Excellent focus! Keep building this habit. Try extending your session by 5 minutes tomorrow."
    return "Stay consistent with your focus sessions. Small improvements compound over time."


def get_remarks(label, violations, focus_completed):
    good, bad = [], []
    if focus_completed:
        good.append("Completed focus session")
    if violations == 0:
        good.append("Zero violations - perfect session")
    if label == 'focused':
        good.append("Maintained focused state throughout")
    if violations >= 3:
        bad.append(f"Broke focus {violations} times")
    if label == 'addicted':
        bad.append("Showing addictive usage patterns")
    if label == 'distracted':
        bad.append("High distraction level detected")
    return {"good": good, "bad": bad}


# ── Routes ────────────────────────────────────────────────

@app.route('/', methods=['GET'])
def index():
    return jsonify({
        'message': 'SoftControl AI Backend is running!',
        'endpoints': {
            'health':   'GET  /health',
            'analyze':  'POST /analyze',
            'simulate': 'GET  /simulate'
        },
        'status': 'ok'
    })


@app.route('/health', methods=['GET'])
def health():
    return jsonify({
        'status': 'ok',
        'message': 'SoftControl AI Backend is running!',
        'models': ['RandomForest', 'XGBoost', 'KMeans', 'IsolationForest', 'VotingEnsemble'],
        'explainability': 'SHAP enabled',
        'timestamp': datetime.datetime.now().isoformat()
    })


@app.route('/analyze', methods=['POST'])
def analyze():
    data = request.json
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    time_spent      = float(data.get('time_spent', 30))
    app_switches    = float(data.get('app_switches', 5))
    hour_of_day     = float(data.get('hour_of_day', datetime.datetime.now().hour))
    violations      = float(data.get('violations', 0))
    focus_completed = bool(data.get('focus_completed', False))

    features = np.array([[time_spent, app_switches, hour_of_day, violations]])

    rf_label_idx = int(rf.predict(features)[0])
    rf_label     = le.inverse_transform([rf_label_idx])[0]

    risk_score = float(xgb.predict_proba(features)[0][1])

    cluster_id   = int(kmeans.predict(features)[0])
    cluster_name = CLUSTER_NAMES.get(cluster_id, "Regular User")

    anomaly_score = float(iso.decision_function(features)[0])
    is_binge      = bool(iso.predict(features)[0] == -1)

    ensemble_label_idx = int(ensemble.predict(features)[0])
    ensemble_label     = le.inverse_transform([ensemble_label_idx])[0]
    ensemble_proba     = ensemble.predict_proba(features)[0]
    confidence         = float(max(ensemble_proba))

    # SHAP - version-safe extraction
    shap_vals  = explainer.shap_values(features)
    shap_flat  = extract_shap_values(shap_vals, rf_label_idx, len(FEATURES))
    explanations = {
        FEATURES[i]: round(float(shap_flat[i]), 4)
        for i in range(len(FEATURES))
    }
    top_factor = max(explanations, key=lambda k: abs(explanations[k]))

    usage_factor       = min(time_spent / 5, 30)
    self_control_score = max(0, int(100 - (violations * 10) - usage_factor))
    monster_level      = min(5, int(violations) + int(risk_score * 3))
    weekly_prediction  = round(time_spent * 7 / 60, 1)

    return jsonify({
        'label':                    rf_label,
        'ensemble_label':           ensemble_label,
        'confidence':               round(confidence, 2),
        'risk_score':               round(risk_score, 2),
        'self_control_score':       self_control_score,
        'monster_level':            monster_level,
        'cluster':                  cluster_name,
        'cluster_id':               cluster_id,
        'is_binge_session':         is_binge,
        'anomaly_score':            round(anomaly_score, 3),
        'explanations':             explanations,
        'top_factor':               top_factor,
        'insight':                  get_insight(rf_label, risk_score, cluster_name,
                                                int(hour_of_day), int(violations), is_binge),
        'coach_tip':                get_coach_tip(rf_label, int(violations), risk_score),
        'remarks':                  get_remarks(rf_label, int(violations), focus_completed),
        'weekly_screen_time_hours': weekly_prediction,
    })


@app.route('/simulate', methods=['GET'])
def simulate():
    scenarios = [
        {'name': 'Late Night Addict',
         'data': {'time_spent': 280, 'app_switches': 28, 'hour_of_day': 23, 'violations': 4}},
        {'name': 'Morning Worker',
         'data': {'time_spent': 25,  'app_switches': 3,  'hour_of_day': 10, 'violations': 0}},
        {'name': 'Evening Binge',
         'data': {'time_spent': 180, 'app_switches': 22, 'hour_of_day': 20, 'violations': 3}},
    ]

    results = []
    for s in scenarios:
        f = np.array([[s['data']['time_spent'], s['data']['app_switches'],
                       s['data']['hour_of_day'], s['data']['violations']]])
        label    = le.inverse_transform(rf.predict(f))[0]
        risk     = float(xgb.predict_proba(f)[0][1])
        cluster  = CLUSTER_NAMES.get(int(kmeans.predict(f)[0]), "Unknown")
        is_binge = bool(iso.predict(f)[0] == -1)
        score    = max(0, int(100 - s['data']['violations'] * 10 - s['data']['time_spent'] / 5))
        results.append({
            'scenario':           s['name'],
            'label':              label,
            'risk_score':         round(risk, 2),
            'cluster':            cluster,
            'is_binge':           is_binge,
            'self_control_score': score,
            'monster_level':      min(5, s['data']['violations'] + int(risk * 3))
        })

    return jsonify({'scenarios': results})


if __name__ == '__main__':
    print("\nStarting SoftControl AI Backend...")
    print("   GET  /health    - check server status")
    print("   POST /analyze   - analyze behavior data")
    print("   GET  /simulate  - demo scenarios for judges")
    print("\n   Server: http://localhost:5000\n")
    app.run(debug=True, host='0.0.0.0', port=5000)
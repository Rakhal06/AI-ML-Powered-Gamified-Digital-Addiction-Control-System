"""Personalized model trainer — fits per-user RF when 50+ sessions available."""
import numpy as np, os, joblib

PERSONAL_DIR = 'models/personal/'
MIN_SESSIONS  = 50

FEATURES = [
    'time_spent','app_switches','hour_of_day','violations',
    'session_gap','previous_usage','focus_sessions','day_of_week',
    'interaction_intensity','discipline_score','usage_pressure','night_flag',
    'location_type_enc','day_type_enc','battery_level','headphone_connected'
]

def personal_model_path(user_id): return os.path.join(PERSONAL_DIR, f'{user_id}_rf.pkl')
def has_personal_model(user_id):  return os.path.exists(personal_model_path(user_id))

def session_to_row(s):
    try:
        ts=float(s.get('time_spent',30)); sw=float(s.get('app_switches',5))
        hr=float(s.get('hour_of_day',12)); vi=float(s.get('violations',0))
        sg=float(s.get('session_gap',15)); pu=float(s.get('previous_usage',30))
        fs=float(s.get('focus_sessions',1)); dw=float(s.get('day_of_week',0))
        loc={'home':0,'college':1,'other':2}.get(s.get('location_type','other'),2)
        dt=0 if s.get('day_type','weekday')=='weekday' else 1
        bl=float(s.get('battery_level',100)); hc=float(1 if s.get('headphone_connected',False) else 0)
        return [ts,sw,hr,vi,sg,pu,fs,dw,ts*sw,fs*10-vi*5,pu/(sg+1),
                1.0 if(hr>=22 or hr<5) else 0.0,loc,dt,bl,hc]
    except Exception: return None

def train_personal_model(user_id, sessions):
    if len(sessions) < MIN_SESSIONS:
        return False
    try:
        from sklearn.ensemble import RandomForestClassifier
        le = joblib.load('models/label_encoder.pkl')
        X, y = [], []
        for s in sessions:
            row = session_to_row(s)
            lbl = s.get('label','focused')
            if row and lbl in le.classes_:
                X.append(row); y.append(lbl)
        if len(X) < MIN_SESSIONS: return False
        X = np.array(X,dtype=float)
        y_enc = le.transform(y)
        rf = RandomForestClassifier(n_estimators=100, random_state=42)
        rf.fit(X, y_enc)
        os.makedirs(PERSONAL_DIR, exist_ok=True)
        joblib.dump(rf, personal_model_path(user_id))
        print(f'Personal model saved: {user_id[:8]}')
        return True
    except Exception as e:
        print(f'Personal training failed: {e}'); return False

def predict_with_best(user_id, features, le):
    """Returns (label, used_personal_model)."""
    if has_personal_model(user_id):
        try:
            prf = joblib.load(personal_model_path(user_id))
            idx = int(prf.predict(features)[0])
            return le.inverse_transform([idx])[0], True
        except Exception: pass
    grf = joblib.load('models/rf_classifier.pkl')
    idx = int(grf.predict(features)[0])
    return le.inverse_transform([idx])[0], False
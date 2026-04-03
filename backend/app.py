from flask import Flask, request, jsonify
from flask_cors import CORS
import joblib, numpy as np, datetime, os, threading

from user_db import (save_session, get_user_sessions, get_session_count,
                     upsert_user, update_leaderboard, get_leaderboard,
                     get_user_rank, get_analytics, save_rl_feedback,
                     save_usage_log, get_or_create_missions, update_mission_progress,
                     get_user_profile, get_usage_trends, get_peak_hours)
from rl_agent import get_action, update as rl_update
from lstm_model import predict_sequence, train_lstm
from personal_model_trainer import (predict_with_best, train_personal_model,
                                     session_to_row)

app = Flask(__name__)
CORS(app)

# ── Feature list (16 features) ────────────────────────────────
FEATURES = [
    'time_spent','app_switches','hour_of_day','violations',
    'session_gap','previous_usage','focus_sessions','day_of_week',
    'interaction_intensity','discipline_score','usage_pressure','night_flag',
    'location_type_enc','day_type_enc','battery_level','headphone_connected'
]
N_FEATURES = len(FEATURES)
CLUSTER_NAMES = {0:"Night Owl", 1:"Binge User", 2:"Impulsive User", 3:"Regular User"}

# ── Auto-generate models if missing / outdated ────────────────
def need_regen():
    required = ['models/rf_classifier.pkl','models/xgb_relapse.pkl',
                 'models/kmeans.pkl','models/isolation_forest.pkl',
                 'models/ensemble.pkl','models/label_encoder.pkl','models/scaler.pkl']
    if not all(os.path.exists(f) for f in required): return True
    try:
        rf = joblib.load('models/rf_classifier.pkl')
        return rf.n_features_in_ != N_FEATURES
    except Exception: return True

def generate_models():
    from sklearn.ensemble import RandomForestClassifier, IsolationForest, VotingClassifier
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import LabelEncoder, StandardScaler
    from sklearn.linear_model import LogisticRegression
    from sklearn.svm import SVC
    from xgboost import XGBClassifier
    import shap as shap_lib

    os.makedirs('models', exist_ok=True)
    print("Auto-generating models with 16 features...")
    np.random.seed(42); N = 2000

    hours    = np.random.randint(0,24,N).astype(float)
    ts       = np.random.randint(5,300,N).astype(float)
    sw       = np.random.randint(0,40,N).astype(float)
    vi       = np.random.randint(0,8,N).astype(float)
    sg       = np.random.randint(1,60,N).astype(float)
    pu       = np.random.randint(10,400,N).astype(float)
    fs       = np.random.randint(0,8,N).astype(float)
    dw       = np.random.randint(0,7,N).astype(float)
    loc_enc  = np.random.randint(0,3,N).astype(float)
    dt_enc   = np.random.randint(0,2,N).astype(float)
    bl       = np.random.randint(10,100,N).astype(float)
    hc       = np.random.randint(0,2,N).astype(float)
    ii       = ts * sw
    ds       = fs*10 - vi*5
    up       = pu / (sg+1)
    nf       = ((hours>=22)|(hours<5)).astype(float)

    X = np.column_stack([ts,sw,hours,vi,sg,pu,fs,dw,ii,ds,up,nf,loc_enc,dt_enc,bl,hc])
    labels = np.where((ts>150)|(vi>=4),'addicted',
             np.where((sw>15)|(vi>=2),'distracted','focused'))
    le = LabelEncoder(); y = le.fit_transform(labels)
    joblib.dump(le,'models/label_encoder.pkl')

    rf = RandomForestClassifier(n_estimators=150, random_state=42)
    rf.fit(X, y); joblib.dump(rf,'models/rf_classifier.pkl')

    y_bin = (labels=='addicted').astype(int)
    xgb = XGBClassifier(n_estimators=150, random_state=42, eval_metric='logloss')
    xgb.fit(X, y_bin); joblib.dump(xgb,'models/xgb_relapse.pkl')

    scaler = StandardScaler()
    X_s = scaler.fit_transform(X); joblib.dump(scaler,'models/scaler.pkl')

    kmeans = KMeans(n_clusters=4, random_state=42, n_init=10)
    kmeans.fit(X_s); joblib.dump(kmeans,'models/kmeans.pkl')

    iso = IsolationForest(contamination=0.1, random_state=42)
    iso.fit(X); joblib.dump(iso,'models/isolation_forest.pkl')

    lr=LogisticRegression(max_iter=1000,random_state=42)
    svc=SVC(probability=True,random_state=42)
    ens=VotingClassifier(estimators=[('rf',rf),('lr',lr),('svc',svc)],voting='soft')
    ens.fit(X_s,y); joblib.dump(ens,'models/ensemble.pkl')

    explainer = shap_lib.TreeExplainer(rf)
    joblib.dump(explainer,'models/shap_explainer.pkl')
    with open('models/model_version.txt','w') as f: f.write('v3_16features')
    print("Models generated!")

if need_regen():
    print("Models missing or outdated — regenerating...")
    generate_models()

print("Loading models...")
rf       = joblib.load('models/rf_classifier.pkl')
xgb      = joblib.load('models/xgb_relapse.pkl')
kmeans   = joblib.load('models/kmeans.pkl')
iso      = joblib.load('models/isolation_forest.pkl')
ensemble = joblib.load('models/ensemble.pkl')
le       = joblib.load('models/label_encoder.pkl')
scaler   = joblib.load('models/scaler.pkl')
explainer= joblib.load('models/shap_explainer.pkl')
print("All models loaded!")

# ── Feature builder ───────────────────────────────────────────
def build_features(data, prev_sessions):
    ts = float(data.get('time_spent',30))
    sw = float(data.get('app_switches',5))
    hr = float(data.get('hour_of_day', datetime.datetime.now().hour))
    vi = float(data.get('violations',0))
    fc = bool(data.get('focus_completed',False))
    loc= data.get('location_type','other')
    dt = data.get('day_type','weekday')
    bl = float(data.get('battery_level',100))
    hc = float(1 if data.get('headphone_connected',False) else 0)
    dw = float(datetime.datetime.now().weekday())

    if prev_sessions:
        last = prev_sessions[0]
        pu   = float(last.get('time_spent', ts*0.8))
        try:
            last_ts = datetime.datetime.fromisoformat(last['timestamp'])
            sg = max(1.0, (datetime.datetime.now()-last_ts).total_seconds()/60)
        except Exception: sg = 15.0
        today_str = datetime.datetime.now().strftime('%Y-%m-%d')
        fs = float(sum(1 for s in prev_sessions
                       if s.get('focus_completed',0) and
                          str(s.get('timestamp',''))[:10]==today_str))
        fs += 1.0 if fc else 0.0
    else:
        sg = 15.0; pu = ts*0.8; fs = 1.0 if fc else 0.0

    loc_enc = {'home':0,'college':1,'other':2}.get(loc, 2)
    dt_enc  = 0 if dt == 'weekday' else 1
    ii = ts * sw
    ds = fs*10 - vi*5
    up = pu / (sg+1)
    nf = 1.0 if (hr>=22 or hr<5) else 0.0

    return np.array([[ts,sw,hr,vi,sg,pu,fs,dw,ii,ds,up,nf,loc_enc,dt_enc,bl,hc]]), \
           {'ts':ts,'sw':sw,'hr':hr,'vi':vi,'sg':sg,'pu':pu,'fs':fs,'dw':dw,
            'loc':loc,'dt':dt,'bl':bl,'hc':hc}

# ── SHAP extraction ───────────────────────────────────────────
def extract_shap(shap_vals, label_idx):
    if isinstance(shap_vals, list):
        return np.array(shap_vals[label_idx]).flatten()[:N_FEATURES]
    arr = np.array(shap_vals)
    if arr.ndim==3:
        return arr[0,:,label_idx] if arr.shape[0]==1 else arr[0,:,label_idx]
    return arr.flatten()[:N_FEATURES]

# ── XP calculation ────────────────────────────────────────────
def calc_xp(focus_completed, violations, streak):
    if focus_completed:
        xp = 50 if violations==0 else 25 if violations==1 else 10 if violations==2 else -20
    else:
        xp = -20 if violations>=3 else 5
    xp += min(streak*5, 50)
    return max(xp, -50)

# ── Insight / tip / remarks ────────────────────────────────────
def get_insight(label, risk, cluster, hour, violations, is_binge):
    parts=[]
    if label=='addicted':   parts.append("You are showing addictive behavior patterns.")
    elif label=='distracted': parts.append("You are getting distracted. Try reducing app switching.")
    else: parts.append("Great job! You are staying focused.")
    if risk>0.7:  parts.append(f"Relapse risk HIGH ({int(risk*100)}%). Take a break now.")
    elif risk>0.4: parts.append(f"Relapse risk MODERATE ({int(risk*100)}%). Stay mindful.")
    if is_binge:  parts.append("Session flagged as binge by anomaly detection.")
    if 22<=hour or hour<5: parts.append("You tend to lose focus late at night.")
    parts.append(f"Profile: {cluster}.")
    return " ".join(parts)

def get_coach_tip(label, violations, risk):
    if violations>=3: return "You broke focus 3+ times. Try a 10-min digital detox."
    elif label=='addicted' and risk>0.6: return "High addiction risk. Enable strict mode and set a 20-min session limit."
    elif label=='distracted': return "You keep switching apps. Try Pomodoro: 25 min focus, 5 min break."
    elif label=='focused': return "Excellent focus! Keep building this habit."
    return "Stay consistent with focus sessions. Small improvements compound."

def get_remarks(label, violations, focus_completed):
    good,bad=[],[]
    if focus_completed: good.append("Completed focus session")
    if violations==0:   good.append("Zero violations — perfect session")
    if label=='focused': good.append("Maintained focused state")
    if violations>=3:   bad.append(f"Broke focus {violations} times")
    if label=='addicted': bad.append("Showing addictive usage patterns")
    if label=='distracted': bad.append("High distraction level")
    return {"good":good,"bad":bad}

# ── Mission progress auto-update after analysis ────────────────
def auto_update_missions(user_id, label, risk_score, violations, focus_completed, self_control_score):
    """Update mission progress based on session outcome."""
    if focus_completed and violations == 0:
        update_mission_progress(user_id, 'zero_violation_session')
    if focus_completed:
        update_mission_progress(user_id, 'complete_two_sessions')
    if self_control_score > 75:
        update_mission_progress(user_id, 'improve_score')
    if risk_score < 0.30:
        update_mission_progress(user_id, 'low_risk')
    if label != 'addicted':
        update_mission_progress(user_id, 'zero_binge_week')

# ── Routes ─────────────────────────────────────────────────────

@app.route('/', methods=['GET'])
def index():
    return jsonify({'message':'SoftControl AI Backend v3 running!','status':'ok',
                    'endpoints':['/health','/analyze','/predict_sequence',
                                 '/check_intervention','/submit_training_data',
                                 '/leaderboard','/user_rank/<user_id>',
                                 '/analytics/<user_id>','/rl_action','/rl_feedback',
                                 '/update_score','/simulate','/missions/<user_id>',
                                 '/log_usage','/usage_trends/<user_id>',
                                 '/retrain_lstm']})

@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status':'ok','models_loaded':True,'features':N_FEATURES,
                    'timestamp':datetime.datetime.now().isoformat()})

@app.route('/analyze', methods=['POST'])
def analyze():
    data = request.json
    if not data: return jsonify({'error':'No data'}), 400

    user_id = data.get('user_id','global')
    if user_id != 'global': upsert_user(user_id)

    prev_sessions = get_user_sessions(user_id, limit=10) if user_id!='global' else []
    features, raw = build_features(data, prev_sessions)
    features_s    = scaler.transform(features)

    # Classification (personal model first, fallback to global)
    rf_label, used_personal = predict_with_best(user_id, features, le)
    rf_label_idx = int(le.transform([rf_label])[0])

    # Relapse risk
    risk_score = float(xgb.predict_proba(features)[0][1])

    # Cluster
    cluster_id   = int(kmeans.predict(features_s)[0])
    cluster_name = CLUSTER_NAMES.get(cluster_id, "Regular User")

    # Anomaly
    anomaly_score = float(iso.decision_function(features)[0])
    is_binge      = bool(iso.predict(features)[0]==-1)

    # Ensemble
    ens_label_idx = int(ensemble.predict(features_s)[0])
    ens_label     = le.inverse_transform([ens_label_idx])[0]
    ens_proba     = ensemble.predict_proba(features_s)[0]
    confidence    = float(max(ens_proba))

    # SHAP
    try:
        shap_vals = explainer.shap_values(features)
        shap_flat = extract_shap(shap_vals, rf_label_idx)
        explanations = {FEATURES[i]: round(float(shap_flat[i]),4) for i in range(N_FEATURES)}
        top_factor = max(explanations, key=lambda k: abs(explanations[k]))
    except Exception:
        explanations = {f:0.0 for f in FEATURES}
        top_factor = 'time_spent'

    usage_factor       = min(raw['ts']/5, 30)
    self_control_score = max(0, int(100 - raw['vi']*10 - usage_factor))
    monster_level      = min(5, int(raw['vi']) + int(risk_score*3))
    weekly_prediction  = round(raw['ts']*7/60, 1)
    focus_completed    = bool(data.get('focus_completed',False))
    violations         = int(raw['vi'])

    # XP
    streak    = data.get('streak', 0)
    xp_earned = calc_xp(focus_completed, violations, streak)

    # LSTM trend from user history
    lstm_data = {'lstm_risk':risk_score,'trend':'stable','sessions_analyzed':0}
    if len(prev_sessions) >= 2:
        try: lstm_data = predict_sequence(prev_sessions)
        except Exception: pass

    # RL action
    rl_data = get_action(risk_score, int(raw['hr']), violations, streak)

    # Save session and update leaderboard
    if user_id != 'global':
        session_record = {**{k:v for k,v in data.items()},
                          'label':rf_label,'risk_score':risk_score,
                          'self_control_score':self_control_score,
                          'cluster':cluster_name,'monster_level':monster_level,
                          'xp_earned':xp_earned,'focus_completed':focus_completed}
        save_session(user_id, session_record)
        update_leaderboard(user_id, data.get('display_name','Player'), self_control_score, max(xp_earned,0))

        # Update mission progress
        auto_update_missions(user_id, rf_label, risk_score, violations,
                             focus_completed, self_control_score)

        # Async personal model training check (TE-2)
        count = get_session_count(user_id)
        if count >= 50 and count % 10 == 0:
            sessions_for_training = get_user_sessions(user_id, limit=200)
            t = threading.Thread(target=train_personal_model, args=(user_id, sessions_for_training))
            t.daemon = True; t.start()

        # TE-3: Auto-trigger LSTM training after 100+ total sessions
        if count == 100 or (count > 100 and count % 50 == 0):
            all_sessions = get_user_sessions(user_id, limit=300)
            t2 = threading.Thread(target=train_lstm, args=(all_sessions,))
            t2.daemon = True; t2.start()

    return jsonify({
        'label':rf_label, 'ensemble_label':ens_label,
        'confidence':round(confidence,2), 'risk_score':round(risk_score,2),
        'self_control_score':self_control_score, 'monster_level':monster_level,
        'cluster':cluster_name, 'cluster_id':cluster_id,
        'is_binge_session':is_binge, 'anomaly_score':round(anomaly_score,3),
        'explanations':explanations, 'top_factor':top_factor,
        'insight':get_insight(rf_label,risk_score,cluster_name,int(raw['hr']),violations,is_binge),
        'coach_tip':get_coach_tip(rf_label,violations,risk_score),
        'remarks':get_remarks(rf_label,violations,focus_completed),
        'weekly_screen_time_hours':weekly_prediction,
        'xp_earned':xp_earned,
        'lstm_risk':lstm_data['lstm_risk'],
        'lstm_trend':lstm_data['trend'],
        'rl_action':rl_data['action_name'],
        'rl_message':rl_data['message'],
        'used_personal_model':used_personal,
    })

@app.route('/predict_sequence', methods=['POST'])
def predict_seq():
    data = request.json
    sessions = data.get('sessions', [])
    if not sessions: return jsonify({'error':'No sessions provided'}), 400
    result = predict_sequence(sessions)
    return jsonify(result)

@app.route('/check_intervention', methods=['POST'])
def check_intervention():
    data = request.json
    user_id = data.get('user_id','global')
    risk    = float(data.get('risk_score', 0))
    hour    = int(data.get('hour_of_day', datetime.datetime.now().hour))
    violations = int(data.get('violations', 0))
    recent_sessions = data.get('recent_sessions', [])

    # TE-4: LSTM-based short-term risk using sliding window
    if len(recent_sessions) >= 2:
        seq_result = predict_sequence(recent_sessions)
        short_risk = seq_result['lstm_risk']
    else:
        short_risk = risk

    # Boost risk if late night
    if hour >= 22 or hour < 5:
        short_risk = min(1.0, short_risk * 1.2)

    # Boost if violations already happening
    if violations >= 2:
        short_risk = min(1.0, short_risk * 1.15)

    threshold = 0.75
    intervene = short_risk > threshold

    rl = get_action(short_risk, hour, violations, data.get('streak',0))

    msg = rl['message']
    if intervene and not msg:
        msg = 'You are at high risk of distraction in the next 5 minutes. Start a focus session!'

    return jsonify({
        'intervene': intervene,
        'short_term_risk': round(short_risk, 3),
        'threshold': threshold,
        'action': rl['action_name'],
        'message': msg
    })

@app.route('/submit_training_data', methods=['POST'])
def submit_training():
    """TE-2: Accept real session data from devices for training."""
    data = request.json
    user_id  = data.get('user_id','global')
    sessions = data.get('sessions', [])
    if not sessions: return jsonify({'error':'No sessions'}), 400
    for s in sessions:
        s['user_id'] = user_id
        save_session(user_id, s)

    # Check if we now have enough real data to retrain
    count = get_session_count(user_id) if user_id != 'global' else 0
    retrain_triggered = False
    if count >= 50 and count % 10 == 0:
        sessions_for_training = get_user_sessions(user_id, limit=200)
        t = threading.Thread(target=train_personal_model, args=(user_id, sessions_for_training))
        t.daemon = True; t.start()
        retrain_triggered = True

    return jsonify({'saved': len(sessions), 'message': 'Training data received',
                    'retrain_triggered': retrain_triggered})

@app.route('/log_usage', methods=['POST'])
def log_usage():
    """Feature 0: Receive per-app usage logs from Android."""
    data = request.json
    user_id = data.get('user_id', 'global')
    app_logs = data.get('app_logs', [])
    if user_id != 'global':
        save_usage_log(user_id, app_logs)
    return jsonify({'status':'ok', 'logged': len(app_logs)})

@app.route('/leaderboard', methods=['GET'])
def leaderboard():
    limit = int(request.args.get('limit',50))
    return jsonify({'leaderboard': get_leaderboard(limit)})

@app.route('/user_rank/<user_id>', methods=['GET'])
def user_rank(user_id):
    return jsonify(get_user_rank(user_id))

@app.route('/update_score', methods=['POST'])
def update_score():
    data = request.json
    user_id      = data.get('user_id','global')
    display_name = data.get('display_name','Player')
    score        = int(data.get('focus_score', 0))
    xp           = int(data.get('xp_earned', 0))
    update_leaderboard(user_id, display_name, score, xp)
    return jsonify({'status':'ok'})

@app.route('/analytics/<user_id>', methods=['GET'])
def analytics(user_id):
    sessions = get_analytics(user_id)
    if not sessions:
        return jsonify({'sessions':[], 'summary':{}, 'trends':[], 'peak_hours':[]})

    import statistics
    scores  = [s['self_control_score'] for s in sessions if s.get('self_control_score')]
    risks   = [s['risk_score'] for s in sessions if s.get('risk_score') is not None]
    viols   = [s['violations'] for s in sessions if s.get('violations') is not None]
    hours   = [s['hour_of_day'] for s in sessions if s.get('hour_of_day') is not None]

    hour_counts = {}
    for h in hours: hour_counts[str(h)] = hour_counts.get(str(h),0)+1
    peak_hour = max(hour_counts, key=hour_counts.get) if hour_counts else '0'

    total_time = sum(s.get('time_spent',0) for s in sessions)
    completed  = sum(1 for s in sessions if s.get('focus_completed'))

    summary = {
        'total_sessions': len(sessions),
        'completed_sessions': completed,
        'avg_score': round(statistics.mean(scores),1) if scores else 0,
        'avg_risk': round(statistics.mean(risks),3) if risks else 0,
        'total_violations': sum(viols),
        'total_screen_time_min': round(total_time,1),
        'peak_distraction_hour': int(peak_hour),
        'avg_daily_time_min': round(total_time/max(len(set(s['timestamp'][:10] for s in sessions)),1),1),
    }

    # Feature 7: Weekly trends + peak hours
    trends    = get_usage_trends(user_id, days=30)
    peak_hrs  = get_peak_hours(user_id)

    return jsonify({'sessions': sessions[-50:], 'summary': summary,
                    'trends': trends, 'peak_hours': peak_hrs})

@app.route('/usage_trends/<user_id>', methods=['GET'])
def usage_trends(user_id):
    days = int(request.args.get('days', 7))
    trends   = get_usage_trends(user_id, days=days)
    peak_hrs = get_peak_hours(user_id)
    return jsonify({'trends': trends, 'peak_hours': peak_hrs})

@app.route('/rl_action', methods=['GET'])
def rl_action():
    risk       = float(request.args.get('risk',0))
    hour       = int(request.args.get('hour', datetime.datetime.now().hour))
    violations = int(request.args.get('violations',0))
    streak     = int(request.args.get('streak',0))
    return jsonify(get_action(risk, hour, violations, streak))

@app.route('/rl_feedback', methods=['POST'])
def rl_feedback():
    data       = request.json
    user_id    = data.get('user_id','global')
    action     = int(data.get('action',0))
    state      = data.get('state',{})
    next_state = data.get('next_state',{})
    reward     = float(data.get('reward',0))
    rl_update(state, action, reward, next_state)
    save_rl_feedback(user_id, action, list(state.values()) if isinstance(state,dict) else [], reward)
    return jsonify({'status':'ok'})

# ── Feature 4: Missions ────────────────────────────────────────
@app.route('/missions/<user_id>', methods=['GET'])
def missions(user_id):
    upsert_user(user_id)
    result = get_or_create_missions(user_id)
    return jsonify(result)

# ── Feature 7: Retrain LSTM with real data ─────────────────────
@app.route('/retrain_lstm', methods=['POST'])
def retrain_lstm():
    data    = request.json
    user_id = data.get('user_id', 'global')
    sessions = get_user_sessions(user_id, limit=500) if user_id != 'global' else []
    if len(sessions) < 20:
        return jsonify({'status':'skipped','reason':'Not enough sessions for LSTM training (need 20+)'})
    t = threading.Thread(target=train_lstm, args=(sessions,))
    t.daemon = True; t.start()
    return jsonify({'status':'training_started','sessions_used':len(sessions)})

@app.route('/simulate', methods=['GET'])
def simulate():
    scenarios=[
        {'name':'Late Night Addict','data':{'time_spent':280,'app_switches':28,'hour_of_day':23,'violations':4,'location_type':'home','day_type':'weekday','battery_level':35,'headphone_connected':False}},
        {'name':'Morning Worker',   'data':{'time_spent':25, 'app_switches':3, 'hour_of_day':10,'violations':0,'location_type':'college','day_type':'weekday','battery_level':90,'headphone_connected':True}},
        {'name':'Evening Binge',    'data':{'time_spent':180,'app_switches':22,'hour_of_day':20,'violations':3,'location_type':'home','day_type':'weekend','battery_level':60,'headphone_connected':False}},
    ]
    results=[]
    for s in scenarios:
        f,_  = build_features(s['data'],[])
        fs   = scaler.transform(f)
        lbl  = le.inverse_transform(rf.predict(f))[0]
        risk = float(xgb.predict_proba(f)[0][1])
        cl   = CLUSTER_NAMES.get(int(kmeans.predict(fs)[0]),"Regular User")
        binge= bool(iso.predict(f)[0]==-1)
        sc   = max(0,int(100-s['data']['violations']*10-s['data']['time_spent']/5))
        results.append({'scenario':s['name'],'label':lbl,'risk_score':round(risk,2),
                         'cluster':cl,'is_binge':binge,'self_control_score':sc})
    return jsonify({'scenarios':results})

if __name__ == '__main__':
    print("\nSoftControl AI Backend v3 — 16-feature context-aware + LSTM + RL + Missions")
    print("  POST /analyze | GET /leaderboard | POST /check_intervention")
    print("  GET  /missions/<user_id> | GET /usage_trends/<user_id>")
    print("  Server: http://localhost:5000\n")
    app.run(debug=True, host='0.0.0.0', port=5000)
import sqlite3, os, json
from datetime import datetime

DB_PATH = os.path.join(os.path.dirname(__file__), 'data', 'softcontrol.db')

def get_conn():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_conn(); c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS user_sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL, timestamp TEXT NOT NULL,
        time_spent REAL DEFAULT 0, app_switches INTEGER DEFAULT 0,
        hour_of_day INTEGER DEFAULT 0, violations INTEGER DEFAULT 0,
        focus_completed INTEGER DEFAULT 0,
        location_type TEXT DEFAULT 'other', day_type TEXT DEFAULT 'weekday',
        battery_level INTEGER DEFAULT 100, headphone_connected INTEGER DEFAULT 0,
        label TEXT DEFAULT '', risk_score REAL DEFAULT 0,
        self_control_score INTEGER DEFAULT 0, cluster TEXT DEFAULT '',
        monster_level INTEGER DEFAULT 0, xp_earned INTEGER DEFAULT 0)''')

    c.execute('''CREATE TABLE IF NOT EXISTS user_profiles (
        user_id TEXT PRIMARY KEY, display_name TEXT DEFAULT 'Anonymous',
        total_xp INTEGER DEFAULT 0, level INTEGER DEFAULT 1,
        streak INTEGER DEFAULT 0, last_session_date TEXT DEFAULT '',
        session_count INTEGER DEFAULT 0, created_at TEXT DEFAULT '')''')

    c.execute('''CREATE TABLE IF NOT EXISTS leaderboard (
        user_id TEXT PRIMARY KEY, display_name TEXT DEFAULT 'Anonymous',
        focus_score INTEGER DEFAULT 0, weekly_xp INTEGER DEFAULT 0,
        last_updated TEXT DEFAULT '')''')

    c.execute('''CREATE TABLE IF NOT EXISTS rl_feedback (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT, action INTEGER, state TEXT, reward REAL, timestamp TEXT)''')

    # Feature 0: Continuous per-app usage tracking
    c.execute('''CREATE TABLE IF NOT EXISTS usage_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL, timestamp TEXT NOT NULL,
        package_name TEXT DEFAULT '', app_name TEXT DEFAULT '',
        duration_minutes REAL DEFAULT 0, category TEXT DEFAULT 'other')''')

    # Feature 4: Missions / daily challenges
    c.execute('''CREATE TABLE IF NOT EXISTS user_missions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL, mission_key TEXT NOT NULL,
        mission_title TEXT DEFAULT '', mission_desc TEXT DEFAULT '',
        xp_reward INTEGER DEFAULT 20, target INTEGER DEFAULT 1,
        progress INTEGER DEFAULT 0, completed INTEGER DEFAULT 0,
        mission_type TEXT DEFAULT 'daily',
        created_date TEXT DEFAULT '', completed_date TEXT DEFAULT '')''')

    # Feature 5: Social challenges between users
    c.execute('''CREATE TABLE IF NOT EXISTS challenges (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        challenger_id TEXT NOT NULL, challenged_id TEXT NOT NULL,
        challenge_type TEXT DEFAULT 'focus_score',
        status TEXT DEFAULT 'pending',
        created_at TEXT DEFAULT '', resolved_at TEXT DEFAULT '',
        winner_id TEXT DEFAULT '')''')

    conn.commit(); conn.close()

def upsert_user(user_id):
    conn = get_conn(); c = conn.cursor()
    c.execute('INSERT OR IGNORE INTO user_profiles (user_id, created_at) VALUES (?,?)',
              (user_id, datetime.now().isoformat()))
    conn.commit(); conn.close()

def save_session(user_id, data):
    conn = get_conn(); c = conn.cursor()
    c.execute('''INSERT INTO user_sessions
        (user_id,timestamp,time_spent,app_switches,hour_of_day,violations,
         focus_completed,location_type,day_type,battery_level,headphone_connected,
         label,risk_score,self_control_score,cluster,monster_level,xp_earned)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
        (user_id, datetime.now().isoformat(),
         data.get('time_spent',0), data.get('app_switches',0),
         data.get('hour_of_day',0), data.get('violations',0),
         int(data.get('focus_completed',False)),
         data.get('location_type','other'), data.get('day_type','weekday'),
         data.get('battery_level',100), int(data.get('headphone_connected',False)),
         data.get('label',''), data.get('risk_score',0),
         data.get('self_control_score',0), data.get('cluster',''),
         data.get('monster_level',0), data.get('xp_earned',0)))

    c.execute('''UPDATE user_profiles SET session_count=session_count+1,
                 last_session_date=? WHERE user_id=?''',
              (datetime.now().isoformat(), user_id))

    # Update XP in profile
    xp = int(data.get('xp_earned', 0))
    if xp != 0:
        c.execute('''UPDATE user_profiles SET total_xp=MAX(0, total_xp+?) WHERE user_id=?''',
                  (xp, user_id))

    conn.commit(); conn.close()

def save_usage_log(user_id, app_logs):
    """Save per-app usage data. app_logs = list of {package_name, app_name, duration_minutes, category}"""
    if not app_logs: return
    conn = get_conn(); c = conn.cursor()
    ts = datetime.now().isoformat()
    for log in app_logs:
        c.execute('''INSERT INTO usage_logs
            (user_id, timestamp, package_name, app_name, duration_minutes, category)
            VALUES (?,?,?,?,?,?)''',
            (user_id, ts,
             log.get('package_name',''), log.get('app_name',''),
             log.get('duration_minutes',0), log.get('category','other')))
    conn.commit(); conn.close()

def get_user_sessions(user_id, limit=200):
    conn = get_conn(); c = conn.cursor()
    c.execute('SELECT * FROM user_sessions WHERE user_id=? ORDER BY timestamp DESC LIMIT ?',
              (user_id, limit))
    rows = [dict(r) for r in c.fetchall()]
    conn.close(); return rows

def get_session_count(user_id):
    conn = get_conn(); c = conn.cursor()
    c.execute('SELECT COUNT(*) FROM user_sessions WHERE user_id=?', (user_id,))
    n = c.fetchone()[0]; conn.close(); return n

def update_leaderboard(user_id, display_name, focus_score, xp):
    conn = get_conn(); c = conn.cursor()
    now = datetime.now().isoformat()
    c.execute('''INSERT INTO leaderboard (user_id,display_name,focus_score,weekly_xp,last_updated)
                 VALUES (?,?,?,?,?)
                 ON CONFLICT(user_id) DO UPDATE SET
                 display_name=excluded.display_name,
                 focus_score=MAX(leaderboard.focus_score, excluded.focus_score),
                 weekly_xp=leaderboard.weekly_xp+excluded.weekly_xp,
                 last_updated=excluded.last_updated''',
              (user_id, display_name, focus_score, xp, now))
    conn.commit(); conn.close()

def get_leaderboard(limit=50):
    conn = get_conn(); c = conn.cursor()
    c.execute('SELECT user_id,display_name,focus_score,weekly_xp FROM leaderboard ORDER BY focus_score DESC LIMIT ?', (limit,))
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    for i, row in enumerate(rows): row['rank'] = i + 1
    return rows

def get_user_rank(user_id):
    conn = get_conn(); c = conn.cursor()
    c.execute('''SELECT COUNT(*)+1 FROM leaderboard WHERE
                 focus_score > COALESCE((SELECT focus_score FROM leaderboard WHERE user_id=?),0)''', (user_id,))
    rank = c.fetchone()[0]
    c.execute('SELECT * FROM leaderboard WHERE user_id=?', (user_id,))
    profile = c.fetchone()
    conn.close()
    return {'rank': rank, 'profile': dict(profile) if profile else None}

def get_analytics(user_id):
    conn = get_conn(); c = conn.cursor()
    c.execute('''SELECT * FROM user_sessions WHERE user_id=?
                 AND timestamp >= datetime('now','-30 days') ORDER BY timestamp ASC''', (user_id,))
    rows = [dict(r) for r in c.fetchall()]; conn.close(); return rows

def save_rl_feedback(user_id, action, state, reward):
    conn = get_conn(); c = conn.cursor()
    c.execute('INSERT INTO rl_feedback (user_id,action,state,reward,timestamp) VALUES (?,?,?,?,?)',
              (user_id, action, json.dumps(state), reward, datetime.now().isoformat()))
    conn.commit(); conn.close()

# ── Feature 4: Missions ───────────────────────────────────────

DAILY_MISSIONS_POOL = [
    ('zero_violation_session', 'Perfect Focus Session', 'Complete 1 focus session with 0 violations', 30, 1),
    ('complete_two_sessions', 'Double Focus', 'Complete 2 focus sessions today', 25, 2),
    ('under_2h_screen', 'Screen Discipline', 'Keep total screen time under 2 hours today', 20, 1),
    ('improve_score', 'Score Boost', 'Achieve a self-control score above 75', 25, 1),
    ('low_risk', 'Risk Reducer', 'Get your relapse risk below 30%', 20, 1),
]

WEEKLY_MISSIONS_POOL = [
    ('7day_streak', 'Week Warrior', 'Maintain a 7-day focus streak', 100, 7),
    ('10_sessions_week', 'Session Master', 'Complete 10 focus sessions this week', 80, 10),
    ('avg_score_70', 'Consistent Performer', 'Keep average score above 70 for the week', 75, 1),
    ('zero_binge_week', 'No Binge Week', 'Avoid any binge sessions for 7 days', 90, 7),
]

def get_or_create_missions(user_id):
    """Get today's missions for user, creating them if they don't exist."""
    conn = get_conn(); c = conn.cursor()
    today = datetime.now().strftime('%Y-%m-%d')

    # Check if daily missions already created for today
    c.execute('''SELECT * FROM user_missions WHERE user_id=? AND mission_type='daily'
                 AND created_date=? ORDER BY id''', (user_id, today))
    daily = [dict(r) for r in c.fetchall()]

    if not daily:
        # Create 3 random daily missions
        import random
        selected = random.sample(DAILY_MISSIONS_POOL, min(3, len(DAILY_MISSIONS_POOL)))
        for key, title, desc, xp, target in selected:
            c.execute('''INSERT INTO user_missions
                (user_id, mission_key, mission_title, mission_desc, xp_reward, target,
                 progress, completed, mission_type, created_date)
                VALUES (?,?,?,?,?,?,0,0,'daily',?)''',
                (user_id, key, title, desc, xp, target, today))
        conn.commit()
        c.execute('''SELECT * FROM user_missions WHERE user_id=? AND mission_type='daily'
                     AND created_date=?''', (user_id, today))
        daily = [dict(r) for r in c.fetchall()]

    # Get/create weekly missions
    week_start = datetime.now().strftime('%Y-W%W')
    c.execute('''SELECT * FROM user_missions WHERE user_id=? AND mission_type='weekly'
                 AND created_date=? ORDER BY id''', (user_id, week_start))
    weekly = [dict(r) for r in c.fetchall()]

    if not weekly:
        import random
        selected = random.sample(WEEKLY_MISSIONS_POOL, min(2, len(WEEKLY_MISSIONS_POOL)))
        for key, title, desc, xp, target in selected:
            c.execute('''INSERT INTO user_missions
                (user_id, mission_key, mission_title, mission_desc, xp_reward, target,
                 progress, completed, mission_type, created_date)
                VALUES (?,?,?,?,?,?,0,0,'weekly',?)''',
                (user_id, key, title, desc, xp, target, week_start))
        conn.commit()
        c.execute('''SELECT * FROM user_missions WHERE user_id=? AND mission_type='weekly'
                     AND created_date=?''', (user_id, week_start))
        weekly = [dict(r) for r in c.fetchall()]

    conn.close()
    return {'daily': daily, 'weekly': weekly}

def update_mission_progress(user_id, mission_key, increment=1):
    """Increment progress on a mission and mark complete if target reached."""
    conn = get_conn(); c = conn.cursor()
    today = datetime.now().strftime('%Y-%m-%d')
    week_start = datetime.now().strftime('%Y-W%W')

    # Find matching incomplete missions
    c.execute('''SELECT * FROM user_missions WHERE user_id=? AND mission_key=?
                 AND completed=0 AND (created_date=? OR created_date=?)''',
              (user_id, mission_key, today, week_start))
    missions = [dict(r) for r in c.fetchall()]

    xp_earned = 0
    for m in missions:
        new_progress = m['progress'] + increment
        completed = 1 if new_progress >= m['target'] else 0
        c.execute('''UPDATE user_missions SET progress=?, completed=?, completed_date=?
                     WHERE id=?''',
                  (new_progress, completed,
                   datetime.now().isoformat() if completed else '', m['id']))
        if completed:
            xp_earned += m['xp_reward']
            # Award XP to profile
            c.execute('UPDATE user_profiles SET total_xp=total_xp+? WHERE user_id=?',
                      (m['xp_reward'], user_id))

    conn.commit(); conn.close()
    return xp_earned

def get_user_profile(user_id):
    conn = get_conn(); c = conn.cursor()
    c.execute('SELECT * FROM user_profiles WHERE user_id=?', (user_id,))
    row = c.fetchone(); conn.close()
    return dict(row) if row else None

def get_usage_trends(user_id, days=7):
    """Get per-day usage aggregates for the last N days."""
    conn = get_conn(); c = conn.cursor()
    c.execute('''SELECT date(timestamp) as day,
                        SUM(time_spent) as total_time,
                        AVG(self_control_score) as avg_score,
                        SUM(violations) as total_violations,
                        COUNT(*) as session_count
                 FROM user_sessions WHERE user_id=?
                 AND timestamp >= datetime('now', ?)
                 GROUP BY date(timestamp) ORDER BY day ASC''',
              (user_id, f'-{days} days'))
    rows = [dict(r) for r in c.fetchall()]; conn.close(); return rows

def get_peak_hours(user_id):
    """Get which hours of day user is most distracted."""
    conn = get_conn(); c = conn.cursor()
    c.execute('''SELECT hour_of_day, COUNT(*) as count,
                        AVG(risk_score) as avg_risk,
                        AVG(violations) as avg_violations
                 FROM user_sessions WHERE user_id=? AND label != 'focused'
                 GROUP BY hour_of_day ORDER BY count DESC LIMIT 5''', (user_id,))
    rows = [dict(r) for r in c.fetchall()]; conn.close(); return rows

init_db()
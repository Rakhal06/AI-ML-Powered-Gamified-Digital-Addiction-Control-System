"""Q-learning RL agent — chooses optimal intervention action per user state.
TE-3: Epsilon-greedy exploration for better policy learning.
"""
import numpy as np, os, json

N_ACTIONS = 4
Q_PATH     = 'models/q_table.npy'
EPS_PATH   = 'models/rl_epsilon.json'

ACTION_NAMES = {0:'none', 1:'show_tip', 2:'send_warning', 3:'suggest_focus'}
ACTION_MSGS  = {
    0: None,
    1: 'Tip: Take a short break from your phone.',
    2: '⚠️ High distraction risk detected. Consider a focus session.',
    3: '🎯 Start a focus session now to protect your score!'
}

def _load_q():
    if os.path.exists(Q_PATH):
        return np.load(Q_PATH)
    return np.zeros((10, 8, 6, 10, N_ACTIONS))

def _save_q(q):
    os.makedirs('models', exist_ok=True)
    np.save(Q_PATH, q)

def _load_epsilon():
    if os.path.exists(EPS_PATH):
        with open(EPS_PATH) as f:
            return json.load(f).get('epsilon', 0.3)
    return 0.3

def _save_epsilon(eps):
    os.makedirs('models', exist_ok=True)
    with open(EPS_PATH, 'w') as f:
        json.dump({'epsilon': eps}, f)

def _idx(risk, hour, violations, streak):
    return (min(int(risk*10), 9), min(hour//3, 7), min(violations, 5), min(streak//7, 9))

def get_action(risk: float, hour: int, violations: int, streak: int = 0) -> dict:
    # Hard rules for extreme states (override Q-table)
    if risk > 0.85 or violations >= 3:
        action = 3
    elif risk > 0.70:
        action = 2
    else:
        q   = _load_q()
        eps = _load_epsilon()
        r, h, v, s = _idx(risk, hour, violations, streak)
        # Epsilon-greedy: explore with probability eps, exploit otherwise
        if np.random.random() < eps:
            action = np.random.randint(0, N_ACTIONS)   # explore
        else:
            action = int(np.argmax(q[r, h, v, s]))     # exploit

    return {'action': action, 'action_name': ACTION_NAMES[action], 'message': ACTION_MSGS[action]}

def update(state: dict, action: int, reward: float, next_state: dict,
           alpha=0.1, gamma=0.9, eps_decay=0.995, eps_min=0.05):
    try:
        q   = _load_q()
        eps = _load_epsilon()

        s  = _idx(state.get('risk', 0), state.get('hour', 0),
                  state.get('violations', 0), state.get('streak', 0))
        ns = _idx(next_state.get('risk', 0), next_state.get('hour', 0),
                  next_state.get('violations', 0), next_state.get('streak', 0))

        curr = q[s[0], s[1], s[2], s[3], action]
        best = np.max(q[ns[0], ns[1], ns[2], ns[3]])
        q[s[0], s[1], s[2], s[3], action] = curr + alpha * (reward + gamma * best - curr)

        _save_q(q)

        # Decay epsilon — become more exploitative over time
        new_eps = max(eps_min, eps * eps_decay)
        _save_epsilon(new_eps)
    except Exception as e:
        print(f'RL update error: {e}')
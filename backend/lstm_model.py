"""LSTM sequential pattern predictor — predicts next-session risk from history.
TE-4: Trains automatically once 100+ real sessions are available.
"""
import numpy as np, os

MODEL_PATH = 'models/lstm_model.h5'
SEQ_LEN    = 7
N_FEATURES = 8   # Extended: time_spent, app_switches, violations, hour, risk, focus_done, battery, headphone

def _norm(s):
    return [
        min(s.get('time_spent', 0) / 300.0, 1.0),
        min(s.get('app_switches', 0) / 40.0, 1.0),
        min(s.get('violations', 0) / 5.0, 1.0),
        s.get('hour_of_day', 12) / 24.0,
        float(s.get('risk_score', 0.5)),
        float(1 if s.get('focus_completed', False) else 0),
        s.get('battery_level', 100) / 100.0,
        float(1 if s.get('headphone_connected', False) else 0),
    ]

def predict_sequence(sessions: list) -> dict:
    if len(sessions) < 2:
        return {'lstm_risk': 0.5, 'trend': 'insufficient_data', 'sessions_analyzed': len(sessions)}

    feats = [_norm(s) for s in sessions[-SEQ_LEN:]]
    while len(feats) < SEQ_LEN:
        feats.insert(0, [0.3, 0.2, 0.1, 0.5, 0.3, 0, 1.0, 0])
    feats = np.array(feats[-SEQ_LEN:])

    lstm_risk = None
    try:
        if os.path.exists(MODEL_PATH):
            import tensorflow as tf
            model = tf.keras.models.load_model(MODEL_PATH)
            input_shape = model.input_shape
            n_feat = input_shape[-1] if len(input_shape) == 3 else N_FEATURES
            if n_feat != N_FEATURES:
                feats_resized = feats[:, :n_feat]
            else:
                feats_resized = feats
            lstm_risk = float(model.predict(feats_resized.reshape(1, SEQ_LEN, n_feat), verbose=0)[0][0])
    except Exception:
        pass

    if lstm_risk is None:
        # Fallback: weighted average of recent risk scores (TE-4 fallback)
        risks = [s.get('risk_score', 0.5) for s in sessions[-5:]]
        w = np.linspace(0.5, 1.0, len(risks)); w /= w.sum()
        lstm_risk = float(np.dot(risks, w))

    recent = [s.get('risk_score', 0.5) for s in sessions[-3:]]
    if len(recent) >= 3:
        trend = 'worsening' if recent[-1] > recent[0] + 0.1 else \
                'improving'  if recent[-1] < recent[0] - 0.1 else 'stable'
    else:
        trend = 'stable'

    return {'lstm_risk': round(lstm_risk, 3), 'trend': trend, 'sessions_analyzed': len(sessions)}


def train_lstm(sessions_list: list):
    """TE-4: Train LSTM on real session data. Called automatically after 100+ sessions."""
    try:
        import tensorflow as tf
        from tensorflow import keras
        if len(sessions_list) < SEQ_LEN + 10:
            print('Not enough sessions for LSTM training.'); return

        feats = np.array([_norm(s) for s in sessions_list])
        risks = np.array([s.get('risk_score', 0.5) for s in sessions_list])

        X, y = [], []
        for i in range(SEQ_LEN, len(feats)):
            X.append(feats[i-SEQ_LEN:i]); y.append(risks[i])
        X, y = np.array(X), np.array(y)

        model = keras.Sequential([
            keras.layers.LSTM(64, input_shape=(SEQ_LEN, N_FEATURES), return_sequences=True),
            keras.layers.Dropout(0.2),
            keras.layers.LSTM(32),
            keras.layers.Dropout(0.2),
            keras.layers.Dense(16, activation='relu'),
            keras.layers.Dense(1, activation='sigmoid')
        ])
        model.compile(optimizer='adam', loss='mse', metrics=['mae'])
        model.fit(X, y, epochs=50, batch_size=16, validation_split=0.2, verbose=0)

        os.makedirs('models', exist_ok=True)
        model.save(MODEL_PATH)
        print(f'LSTM trained on {len(sessions_list)} real sessions → saved to {MODEL_PATH}')
    except ImportError:
        print('TensorFlow not installed. Skipping LSTM training.')
    except Exception as e:
        print(f'LSTM training error: {e}')
        
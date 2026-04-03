"""
Export the RF model to TFLite via a surrogate neural network.
Run this AFTER training models with train_models.py.
Copy output tflite/model.tflite → app/app/src/main/assets/model.tflite
Requires: tensorflow (pip install tensorflow)
"""
import numpy as np, joblib, os

def export():
    try:
        import tensorflow as tf
    except ImportError:
        print("pip install tensorflow first"); return

    rf = joblib.load('models/rf_classifier.pkl')
    n_feat = rf.n_features_in_
    print(f"RF trained with {n_feat} features")

    # Build surrogate dataset
    np.random.seed(42)
    N = 8000
    lo = np.zeros(n_feat)
    hi = np.array([400,40,24,8,60,400,8,7,16000,200,30,1,2,1,100,1])[:n_feat]
    X = np.random.uniform(lo, hi, (N, n_feat)).astype(np.float32)
    y = rf.predict(X).astype(np.int32)
    n_classes = len(rf.classes_)

    # Surrogate NN
    model = tf.keras.Sequential([
        tf.keras.layers.Dense(64, activation='relu', input_shape=(n_feat,)),
        tf.keras.layers.Dense(32, activation='relu'),
        tf.keras.layers.Dense(n_classes, activation='softmax')
    ])
    model.compile(optimizer='adam', loss='sparse_categorical_crossentropy', metrics=['accuracy'])
    model.fit(X, y, epochs=40, batch_size=64, verbose=1, validation_split=0.1)

    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    os.makedirs('tflite', exist_ok=True)
    path = 'tflite/model.tflite'
    with open(path, 'wb') as f: f.write(tflite_model)
    print(f"\nSaved: {path}")
    print("Now copy to: app/app/src/main/assets/model.tflite")

if __name__ == '__main__': export()
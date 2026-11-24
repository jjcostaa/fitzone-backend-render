"""
FitZone VIII Semestre — Pipeline de Minería de Datos II
Entrenamiento, validación cruzada y comparación de modelos avanzados:
  1. Clasificación supervisada (Churn / Riesgo de Cancelación):
     - Random Forest
     - Gradient Boosting
     - Red Neuronal Artificial (MLPClassifier)
  2. Clustering no supervisado (Segmentación de Miembros):
     - K-Means (k=3) con Silhouette Score
"""

import json
import os
from datetime import datetime, timezone
import numpy as np
import pandas as pd
import joblib

from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.neural_network import MLPClassifier
from sklearn.cluster import KMeans
from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    roc_auc_score,
    roc_curve,
    confusion_matrix,
    silhouette_score,
)
from sklearn.model_selection import train_test_split, StratifiedKFold, cross_val_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(BASE_DIR)
ENCUESTAS_PATH = os.path.join(PROJECT_ROOT, "dbfitzone", "fitzone_db.encuestas.json")

NUMERIC_FEATURES = [
    "edad",
    "frecuencia_semanal",
    "tiempo_membresia_meses",
    "pagos_atrasados",
    "satisfaccion_servicio",
    "peso_actual",
    "cambio_peso_mensual",
    "ingresos_mensuales",
]

CATEGORICAL_FEATURES = [
    "genero",
    "objetivo",
    "plan_membresia",
    "ha_asistido_ultima_semana",
    "usa_entrenador_personal",
    "asiste_clases_grupales",
    "lesiones_recientes",
    "tiene_descuento",
]

CLUSTER_FEATURES = [
    "frecuencia_semanal",
    "satisfaccion_servicio",
    "tiempo_membresia_meses",
    "pagos_atrasados",
]


def compute_churn_probability(row: pd.Series) -> float:
    """Función latente basada en reglas de negocio del gimnasio para etiquetado realista."""
    score = -0.4
    if row["frecuencia_semanal"] <= 1:
        score += 1.65
    elif row["frecuencia_semanal"] == 2:
        score += 0.35
    elif row["frecuencia_semanal"] >= 4:
        score -= 1.25

    if row["ha_asistido_ultima_semana"] == "no":
        score += 1.45
    if row["pagos_atrasados"] >= 2:
        score += 1.80
    elif row["pagos_atrasados"] == 1:
        score += 0.65

    if row["satisfaccion_servicio"] <= 4:
        score += 1.75
    elif row["satisfaccion_servicio"] <= 6:
        score += 0.55
    elif row["satisfaccion_servicio"] >= 8:
        score -= 1.10

    if row["plan_membresia"] == "mensual":
        score += 0.55
    elif row["plan_membresia"] == "anual":
        score -= 0.90

    if row["usa_entrenador_personal"] == "si":
        score -= 0.60
    if row["asiste_clases_grupales"] == "si":
        score -= 0.50
    if row["lesiones_recientes"] == "si":
        score += 0.85
    if row["tiene_descuento"] == "si":
        score -= 0.35
    if row["tiempo_membresia_meses"] <= 3:
        score += 0.45
    elif row["tiempo_membresia_meses"] >= 12:
        score -= 0.55

    return float(1.0 / (1.0 + np.exp(-score)))


def build_dataset(target_size: int = 800, seed: int = 42) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    base_records = []
    if os.path.exists(ENCUESTAS_PATH):
        with open(ENCUESTAS_PATH, "r", encoding="utf-8") as f:
            raw = json.load(f)
        for item in raw:
            base_records.append({
                "edad": int(item.get("edad", 28)),
                "genero": str(item.get("genero", "hombre")).lower(),
                "frecuencia_semanal": int(item.get("frecuencia_semanal", 3)),
                "tiempo_membresia_meses": int(item.get("tiempo_membresia_meses", 6)),
                "objetivo": str(item.get("objetivo", "mantener")).lower(),
                "plan_membresia": str(item.get("plan_membresia", "mensual")).lower(),
                "ha_asistido_ultima_semana": str(item.get("ha_asistido_ultima_semana", "si")).lower(),
                "usa_entrenador_personal": str(item.get("usa_entrenador_personal", "no")).lower(),
                "asiste_clases_grupales": str(item.get("asiste_clases_grupales", "si")).lower(),
                "pagos_atrasados": int(item.get("pagos_atrasados", 0)),
                "satisfaccion_servicio": int(item.get("satisfaccion_servicio", 7)),
                "lesiones_recientes": str(item.get("lesiones_recientes", "no")).lower(),
                "peso_actual": float(item.get("peso_actual", 72.0)),
                "cambio_peso_mensual": float(item.get("cambio_peso_mensual", 0.0)),
                "tiene_descuento": str(item.get("tiene_descuento", "no")).lower(),
                "ingresos_mensuales": float(item.get("ingresos_mensuales", 4500.0)),
            })

    rows = list(base_records)
    generos = ["hombre", "mujer"]
    objetivos = ["bajar_peso", "ganar_musculo", "mantener"]
    planes = ["mensual", "trimestral", "anual"]
    sino = ["si", "no"]

    while len(rows) < target_size:
        if base_records and rng.random() < 0.45:
            # Perturbación controlada de registros reales (data augmentation)
            src = dict(base_records[int(rng.integers(0, len(base_records)))])
            src["edad"] = int(np.clip(src["edad"] + rng.integers(-4, 5), 18, 65))
            src["frecuencia_semanal"] = int(np.clip(src["frecuencia_semanal"] + rng.integers(-1, 2), 0, 7))
            src["tiempo_membresia_meses"] = int(np.clip(src["tiempo_membresia_meses"] + rng.integers(-3, 4), 1, 48))
            src["pagos_atrasados"] = int(np.clip(src["pagos_atrasados"] + rng.integers(-1, 2), 0, 5))
            src["satisfaccion_servicio"] = int(np.clip(src["satisfaccion_servicio"] + rng.integers(-2, 3), 1, 10))
            src["peso_actual"] = round(float(np.clip(src["peso_actual"] + rng.normal(0, 3.5), 45, 130)), 1)
            src["cambio_peso_mensual"] = round(float(np.clip(src["cambio_peso_mensual"] + rng.normal(0, 0.4), -4.0, 4.0)), 2)
            rows.append(src)
        else:
            freq = int(rng.choice([0, 1, 2, 3, 4, 5, 6], p=[0.08, 0.16, 0.20, 0.22, 0.18, 0.11, 0.05]))
            rows.append({
                "edad": int(rng.integers(18, 62)),
                "genero": str(rng.choice(generos)),
                "frecuencia_semanal": freq,
                "tiempo_membresia_meses": int(rng.integers(1, 36)),
                "objetivo": str(rng.choice(objetivos)),
                "plan_membresia": str(rng.choice(planes, p=[0.50, 0.28, 0.22])),
                "ha_asistido_ultima_semana": "no" if (freq <= 1 and rng.random() < 0.72) else str(rng.choice(sino, p=[0.78, 0.22])),
                "usa_entrenador_personal": str(rng.choice(sino, p=[0.35, 0.65])),
                "asiste_clases_grupales": str(rng.choice(sino, p=[0.55, 0.45])),
                "pagos_atrasados": int(rng.choice([0, 1, 2, 3], p=[0.55, 0.22, 0.15, 0.08])),
                "satisfaccion_servicio": int(rng.integers(2, 11)),
                "lesiones_recientes": str(rng.choice(sino, p=[0.18, 0.82])),
                "peso_actual": round(float(rng.normal(73, 12)), 1),
                "cambio_peso_mensual": round(float(rng.normal(-0.3, 1.1)), 2),
                "tiene_descuento": str(rng.choice(sino, p=[0.30, 0.70])),
                "ingresos_mensuales": round(float(rng.integers(1800, 12000)), 0),
            })

    df = pd.DataFrame(rows)
    probs = df.apply(compute_churn_probability, axis=1).values
    df["cancelara"] = (rng.random(len(df)) < probs).astype(int)
    return df


def train_and_evaluate():
    df = build_dataset(target_size=800, seed=42)
    # Exportar dataset transparente para trazabilidad y sustentación (Minería de Datos II)
    dataset_csv_path = os.path.join(BASE_DIR, "dataset_fitzone_churn.csv")
    df.to_csv(dataset_csv_path, index=False, encoding="utf-8")

    X = df[NUMERIC_FEATURES + CATEGORICAL_FEATURES]
    y = df["cancelara"]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.20, stratify=y, random_state=42
    )

    preprocessor = ColumnTransformer(
        transformers=[
            ("num", StandardScaler(), NUMERIC_FEATURES),
            ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
        ]
    )

    candidates = {
        "Random Forest": RandomForestClassifier(
            n_estimators=180, max_depth=8, min_samples_leaf=3, random_state=42
        ),
        "Gradient Boosting": GradientBoostingClassifier(
            n_estimators=140, learning_rate=0.07, max_depth=3, random_state=42
        ),
        "Red Neuronal (MLP)": MLPClassifier(
            hidden_layer_sizes=(48, 24),
            activation="relu",
            alpha=0.01,
            max_iter=450,
            random_state=42,
        ),
    }

    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    modelos_reporte = []
    best_name = None
    best_score = -1.0
    best_pipeline = None

    for name, estimator in candidates.items():
        pipe = Pipeline([
            ("preprocessor", preprocessor),
            ("classifier", estimator),
        ])
        cv_f1 = cross_val_score(pipe, X_train, y_train, cv=cv, scoring="f1")
        pipe.fit(X_train, y_train)

        y_pred = pipe.predict(X_test)
        y_proba = pipe.predict_proba(X_test)[:, 1]
        cm = confusion_matrix(y_test, y_pred).tolist()
        fpr, tpr, _ = roc_curve(y_test, y_proba)
        # Muestrear ~18 puntos clave de la curva ROC para graficar en Web y Flutter
        step = max(1, len(fpr) // 18)
        roc_points = [
            {"fpr": round(float(fpr[i]), 3), "tpr": round(float(tpr[i]), 3)}
            for i in range(0, len(fpr), step)
        ]
        if roc_points[-1]["fpr"] != 1.0 or roc_points[-1]["tpr"] != 1.0:
            roc_points.append({"fpr": 1.0, "tpr": 1.0})

        metrics = {
            "modelo": name,
            "accuracy": round(float(accuracy_score(y_test, y_pred)), 4),
            "precision": round(float(precision_score(y_test, y_pred, zero_division=0)), 4),
            "recall": round(float(recall_score(y_test, y_pred, zero_division=0)), 4),
            "f1_score": round(float(f1_score(y_test, y_pred, zero_division=0)), 4),
            "roc_auc": round(float(roc_auc_score(y_test, y_proba)), 4),
            "cv_f1_mean": round(float(cv_f1.mean()), 4),
            "cv_f1_std": round(float(cv_f1.std()), 4),
            "matriz_confusion": {
                "tn": cm[0][0],
                "fp": cm[0][1],
                "fn": cm[1][0],
                "tp": cm[1][1],
            },
            "curva_roc": roc_points,
        }
        modelos_reporte.append(metrics)

        selection_score = (metrics["f1_score"] + metrics["roc_auc"]) / 2.0
        if selection_score > best_score:
            best_score = selection_score
            best_name = name
            best_pipeline = pipe

    # Importancia de variables usando Random Forest entrenado
    rf_pipe = Pipeline([
        ("preprocessor", preprocessor),
        ("classifier", RandomForestClassifier(n_estimators=180, max_depth=8, random_state=42)),
    ])
    rf_pipe.fit(X_train, y_train)
    feature_names = rf_pipe.named_steps["preprocessor"].get_feature_names_out()
    importances = rf_pipe.named_steps["classifier"].feature_importances_
    top_features = sorted(
        [
            {"variable": fn.replace("num__", "").replace("cat__", ""), "importancia": round(float(imp), 4)}
            for fn, imp in zip(feature_names, importances)
        ],
        key=lambda x: x["importancia"],
        reverse=True,
    )[:8]

    # Clustering K-Means (Segmentación de Miembros)
    cluster_pipe = Pipeline([
        ("scaler", StandardScaler()),
        ("kmeans", KMeans(n_clusters=3, n_init=15, random_state=42)),
    ])
    X_cluster = df[CLUSTER_FEATURES]
    cluster_labels_raw = cluster_pipe.fit_predict(X_cluster)
    X_cluster_scaled = cluster_pipe.named_steps["scaler"].transform(X_cluster)
    sil_score = round(float(silhouette_score(X_cluster_scaled, cluster_labels_raw)), 4)

    # Asignar nombres interpretables a los 3 clusters según salud del perfil
    df_tmp = df.copy()
    df_tmp["cluster"] = cluster_labels_raw
    cluster_health = {}
    for cid, grp in df_tmp.groupby("cluster"):
        health = (
            grp["frecuencia_semanal"].mean() * 1.5
            + grp["satisfaccion_servicio"].mean()
            - grp["pagos_atrasados"].mean() * 3.0
        )
        cluster_health[int(cid)] = health

    ordered_cids = [cid for cid, _ in sorted(cluster_health.items(), key=lambda kv: kv[1], reverse=True)]
    cluster_name_map = {
        ordered_cids[0]: "Atleta Comprometido",
        ordered_cids[1]: "Miembro Casual",
        ordered_cids[2]: "Miembro en Riesgo",
    }

    clusters_summary = []
    for cid in ordered_cids:
        grp = df_tmp[df_tmp["cluster"] == cid]
        clusters_summary.append({
            "cluster_id": int(cid),
            "segmento": cluster_name_map[cid],
            "cantidad": int(len(grp)),
            "porcentaje": round(float(len(grp) * 100.0 / len(df_tmp)), 1),
            "frecuencia_promedio": round(float(grp["frecuencia_semanal"].mean()), 2),
            "satisfaccion_promedio": round(float(grp["satisfaccion_servicio"].mean()), 2),
            "pagos_atrasados_promedio": round(float(grp["pagos_atrasados"].mean()), 2),
            "tasa_cancelacion": round(float(grp["cancelara"].mean() * 100.0), 1),
        })

    # Guardar artefactos serializados
    joblib.dump(best_pipeline, os.path.join(BASE_DIR, "best_churn_model.joblib"))
    joblib.dump(
        {"pipeline": cluster_pipe, "name_map": cluster_name_map, "features": CLUSTER_FEATURES},
        os.path.join(BASE_DIR, "kmeans_model.joblib"),
    )

    report = {
        "generado_en": datetime.now(timezone.utc).isoformat(),
        "dataset": {
            "total_registros": int(len(df)),
            "registros_entrenamiento": int(len(X_train)),
            "registros_prueba": int(len(X_test)),
            "tasa_cancelacion_porcentaje": round(float(y.mean() * 100.0), 2),
            "variables_predictoras": len(NUMERIC_FEATURES) + len(CATEGORICAL_FEATURES),
        },
        "modelo_ganador": best_name,
        "comparacion_modelos": modelos_reporte,
        "importancia_variables": top_features,
        "clustering": {
            "algoritmo": "K-Means (k=3)",
            "silhouette_score": sil_score,
            "segmentos": clusters_summary,
        },
    }

    metrics_path = os.path.join(BASE_DIR, "model_metrics.json")
    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print(json.dumps({"status": "OK", "modelo_ganador": best_name, "silhouette_score": sil_score}, ensure_ascii=False))


if __name__ == "__main__":
    train_and_evaluate()

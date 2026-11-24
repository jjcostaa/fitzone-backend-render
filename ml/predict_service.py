"""
Servicio de inferencia rápida para Minería de Datos II — FitZone.
Modos:
  --mode metrics     : Devuelve el reporte JSON con comparación de modelos y métricas.
  --mode individual  : Lee un perfil JSON desde stdin y devuelve riesgo, segmento y recomendaciones.
  --mode batch       : Lee una lista JSON de encuestas desde stdin y devuelve predicciones masivas.
"""

import argparse
import json
import os
import sys
import pandas as pd
import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(BASE_DIR, "best_churn_model.joblib")
KMEANS_PATH = os.path.join(BASE_DIR, "kmeans_model.joblib")
METRICS_PATH = os.path.join(BASE_DIR, "model_metrics.json")

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


def ensure_models():
    if not (os.path.exists(MODEL_PATH) and os.path.exists(KMEANS_PATH) and os.path.exists(METRICS_PATH)):
        from train_models import train_and_evaluate
        train_and_evaluate()


def normalize_record(raw: dict) -> dict:
    return {
        "edad": int(raw.get("edad", 28) or 28),
        "genero": str(raw.get("genero", "hombre") or "hombre").lower(),
        "frecuencia_semanal": int(raw.get("frecuencia_semanal", 3) if raw.get("frecuencia_semanal") is not None else 3),
        "tiempo_membresia_meses": int(raw.get("tiempo_membresia_meses", 6) or 6),
        "objetivo": str(raw.get("objetivo", "mantener") or "mantener").lower(),
        "plan_membresia": str(raw.get("plan_membresia", "mensual") or "mensual").lower(),
        "ha_asistido_ultima_semana": str(raw.get("ha_asistido_ultima_semana", "si") or "si").lower(),
        "usa_entrenador_personal": str(raw.get("usa_entrenador_personal", "no") or "no").lower(),
        "asiste_clases_grupales": str(raw.get("asiste_clases_grupales", "si") or "si").lower(),
        "pagos_atrasados": int(raw.get("pagos_atrasados", 0) if raw.get("pagos_atrasados") is not None else 0),
        "satisfaccion_servicio": int(raw.get("satisfaccion_servicio", 7) or 7),
        "lesiones_recientes": str(raw.get("lesiones_recientes", "no") or "no").lower(),
        "peso_actual": float(raw.get("peso_actual", 72.0) or 72.0),
        "cambio_peso_mensual": float(raw.get("cambio_peso_mensual", 0.0) if raw.get("cambio_peso_mensual") is not None else 0.0),
        "tiene_descuento": str(raw.get("tiene_descuento", "no") or "no").lower(),
        "ingresos_mensuales": float(raw.get("ingresos_mensuales", 4500.0) or 4500.0),
    }


def build_recommendations(rec: dict, prob: float) -> list:
    tips = []
    if rec["frecuencia_semanal"] <= 2:
        tips.append("Programar al menos 3 sesiones semanales con recordatorios en la app FitZone.")
    if rec["ha_asistido_ultima_semana"] == "no":
        tips.append("Retomar con una clase grupal de intensidad moderada esta semana para reactivar el hábito.")
    if rec["asiste_clases_grupales"] == "no":
        tips.append("Inscribirse en clases grupales (Spinning, Zumba o Yoga) aumenta la adherencia en un 35%.")
    if rec["usa_entrenador_personal"] == "no" and prob >= 0.45:
        tips.append("Solicitar una sesión de valoración gratuita con entrenador personal para ajustar objetivos.")
    if rec["pagos_atrasados"] > 0:
        tips.append("Activar alerta de vencimiento o evaluar un plan trimestral con descuento de fidelidad.")
    if rec["lesiones_recientes"] == "si":
        tips.append("Priorizar rutinas de bajo impacto (Pilates/movilidad) y seguimiento de recuperación.")
    if not tips:
        tips.append("¡Excelente constancia! Mantén tu rutina actual y registra tu progreso IMC y pasos diarios.")
    return tips


def predict_records(records: list) -> list:
    ensure_models()
    clf = joblib.load(MODEL_PATH)
    km_bundle = joblib.load(KMEANS_PATH)
    km_pipe = km_bundle["pipeline"]
    name_map = km_bundle["name_map"]
    cluster_features = km_bundle["features"]

    if not records:
        return []

    norm = [normalize_record(r) for r in records]
    df = pd.DataFrame(norm)
    probs = clf.predict_proba(df[NUMERIC_FEATURES + CATEGORICAL_FEATURES])[:, 1]
    clusters = km_pipe.predict(df[cluster_features])

    results = []
    for raw, rec, prob, cid in zip(records, norm, probs, clusters):
        p = round(float(prob), 4)
        riesgo = "ALTO" if p >= 0.65 else ("MEDIO" if p >= 0.35 else "BAJO")
        results.append({
            "nombre": raw.get("nombre", "Usuario FitZone"),
            "correo": raw.get("correo", ""),
            "probabilidad_cancelar": p,
            "probabilidad_porcentaje": round(p * 100.0, 1),
            "prediccion": "SI" if p >= 0.50 else "NO",
            "nivel_riesgo": riesgo,
            "segmento": name_map.get(int(cid), "Miembro Casual"),
            "recomendaciones": build_recommendations(rec, p),
        })
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["metrics", "individual", "batch"], required=True)
    args = parser.parse_args()

    ensure_models()

    if args.mode == "metrics":
        with open(METRICS_PATH, "r", encoding="utf-8") as f:
            print(f.read())
        return

    raw_input = sys.stdin.read().lstrip("\ufeff").strip()
    payload = json.loads(raw_input) if raw_input else ({} if args.mode == "individual" else [])

    if args.mode == "individual":
        res = predict_records([payload])[0]
        print(json.dumps(res, ensure_ascii=False))
    elif args.mode == "batch":
        preds = predict_records(payload)
        alto = sum(1 for p in preds if p["nivel_riesgo"] == "ALTO")
        medio = sum(1 for p in preds if p["nivel_riesgo"] == "MEDIO")
        bajo = sum(1 for p in preds if p["nivel_riesgo"] == "BAJO")
        seg_counts = {}
        for p in preds:
            seg = p["segmento"]
            seg_counts[seg] = seg_counts.get(seg, 0) + 1
        print(json.dumps({
            "total": len(preds),
            "resumen_riesgo": {"alto": alto, "medio": medio, "bajo": bajo},
            "distribucion_segmentos": seg_counts,
            "predicciones": preds,
        }, ensure_ascii=False))


if __name__ == "__main__":
    main()

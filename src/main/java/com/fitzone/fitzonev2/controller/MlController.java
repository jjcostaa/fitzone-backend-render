package com.fitzone.fitzonev2.controller;

import com.fitzone.fitzonev2.service.MlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ml")
@CrossOrigin(origins = "*")
public class MlController {

    @Autowired
    private MlService mlService;

    /**
     * Métricas comparativas de los modelos de Minería de Datos II
     * (Random Forest vs Gradient Boosting vs MLP + K-Means).
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(mlService.getModelMetrics());
    }

    /**
     * Predicción masiva de riesgo de abandono + segmentación K-Means
     * sobre las encuestas almacenadas en MongoDB.
     */
    @GetMapping("/predict/all")
    public ResponseEntity<Map<String, Object>> predictAll() {
        return ResponseEntity.ok(mlService.predictAllFromDatabase());
    }

    /**
     * Predicción individual en tiempo real (usada desde Flutter y Web).
     */
    @PostMapping("/predict/individual")
    public ResponseEntity<Map<String, Object>> predictIndividual(@RequestBody Map<String, Object> profile) {
        return ResponseEntity.ok(mlService.predictIndividual(profile));
    }

    /**
     * Reentrenamiento bajo demanda del pipeline Python.
     */
    @PostMapping("/retrain")
    public ResponseEntity<Map<String, Object>> retrain() {
        return ResponseEntity.ok(mlService.retrainModels());
    }
}

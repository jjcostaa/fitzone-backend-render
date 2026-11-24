package com.fitzone.fitzonev2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitzone.fitzonev2.model.Encuesta;
import com.fitzone.fitzonev2.repository.EncuestaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class MlService {

    @Value("${python.executable:python}")
    private String pythonExecutable;

    @Autowired
    private EncuestaRepository encuestaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Object> cachedMetrics;
    private volatile Map<String, Object> cachedBatchPredictions;
    private volatile long cachedBatchTimestamp = 0L;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    public Map<String, Object> getModelMetrics() {
        if (cachedMetrics != null) {
            return cachedMetrics;
        }
        try {
            Path metricsFile = Path.of("ml", "model_metrics.json");
            if (Files.exists(metricsFile)) {
                String content = Files.readString(metricsFile, StandardCharsets.UTF_8);
                cachedMetrics = objectMapper.readValue(content, new TypeReference<>() {});
                return cachedMetrics;
            }
            String out = runPythonScript(List.of("ml/predict_service.py", "--mode", "metrics"), null);
            cachedMetrics = objectMapper.readValue(out, new TypeReference<>() {});
            return cachedMetrics;
        } catch (Exception e) {
            return buildFallbackMetrics();
        }
    }

    public Map<String, Object> retrainModels() {
        cachedMetrics = null;
        cachedBatchPredictions = null;
        try {
            String out = runPythonScript(List.of("ml/train_models.py"), null);
            return objectMapper.readValue(out, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of(
                    "status", "FALLBACK",
                    "message", "No se pudo ejecutar entrenamiento Python en este entorno: " + e.getMessage()
            );
        }
    }

    public Map<String, Object> predictIndividual(Map<String, Object> profile) {
        try {
            String inputJson = objectMapper.writeValueAsString(profile);
            String out = runPythonScript(List.of("ml/predict_service.py", "--mode", "individual"), inputJson);
            return objectMapper.readValue(out, new TypeReference<>() {});
        } catch (Exception e) {
            return fallbackPredictSingle(profile);
        }
    }

    public Map<String, Object> predictAllFromDatabase() {
        long now = System.currentTimeMillis();
        if (cachedBatchPredictions != null && (now - cachedBatchTimestamp) < CACHE_TTL_MS) {
            return cachedBatchPredictions;
        }
        List<Encuesta> encuestas = encuestaRepository.findAll();
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Encuesta enc : encuestas) {
            Map<String, Object> row = new HashMap<>();
            row.put("nombre", enc.getNombre());
            row.put("correo", enc.getCorreo());
            row.put("edad", enc.getEdad());
            row.put("genero", enc.getGenero());
            row.put("frecuencia_semanal", enc.getFrecuencia_semanal());
            row.put("tiempo_membresia_meses", enc.getTiempo_membresia_meses());
            row.put("objetivo", enc.getObjetivo());
            row.put("plan_membresia", enc.getPlan_membresia());
            row.put("ha_asistido_ultima_semana", enc.getHa_asistido_ultima_semana());
            row.put("usa_entrenador_personal", enc.getUsa_entrenador_personal());
            row.put("asiste_clases_grupales", enc.getAsiste_clases_grupales());
            row.put("pagos_atrasados", enc.getPagos_atrasados());
            row.put("satisfaccion_servicio", enc.getSatisfaccion_servicio());
            row.put("lesiones_recientes", enc.getLesiones_recientes());
            row.put("peso_actual", enc.getPeso_actual());
            row.put("cambio_peso_mensual", enc.getCambio_peso_mensual());
            row.put("tiene_descuento", enc.getTiene_descuento());
            row.put("ingresos_mensuales", enc.getIngresos_mensuales());
            payload.add(row);
        }

        // Si MongoDB aún no tiene encuestas cargadas, usar respaldo local dbfitzone
        if (payload.isEmpty()) {
            payload = loadEncuestasFromLocalBackup();
        }

        try {
            String inputJson = objectMapper.writeValueAsString(payload);
            String out = runPythonScript(List.of("ml/predict_service.py", "--mode", "batch"), inputJson);
            cachedBatchPredictions = objectMapper.readValue(out, new TypeReference<>() {});
            cachedBatchTimestamp = System.currentTimeMillis();
            return cachedBatchPredictions;
        } catch (Exception e) {
            cachedBatchPredictions = fallbackPredictBatch(payload);
            cachedBatchTimestamp = System.currentTimeMillis();
            return cachedBatchPredictions;
        }
    }

    private String runPythonScript(List<String> args, String stdinJson) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File("."));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (stdinJson != null) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        String output = sb.toString().trim();
        int jsonStart = output.indexOf("{");
        if (exitCode == 0 && jsonStart >= 0) {
            return output.substring(jsonStart);
        }
        throw new RuntimeException("Error ejecutando ML Python (exit=" + exitCode + "): " + output);
    }

    private List<Map<String, Object>> loadEncuestasFromLocalBackup() {
        try {
            Path path = Path.of("dbfitzone", "fitzone_db.encuestas.json");
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    private Map<String, Object> fallbackPredictSingle(Map<String, Object> raw) {
        int freq = toInt(raw.get("frecuencia_semanal"), 3);
        int meses = toInt(raw.get("tiempo_membresia_meses"), 6);
        int atrasos = toInt(raw.get("pagos_atrasados"), 0);
        int satisfaccion = toInt(raw.get("satisfaccion_servicio"), 7);
        String asistio = toStr(raw.get("ha_asistido_ultima_semana"), "si");
        String entrenador = toStr(raw.get("usa_entrenador_personal"), "no");
        String clases = toStr(raw.get("asiste_clases_grupales"), "si");
        String lesiones = toStr(raw.get("lesiones_recientes"), "no");
        String plan = toStr(raw.get("plan_membresia"), "mensual");
        String descuento = toStr(raw.get("tiene_descuento"), "no");

        double score = -0.4;
        if (freq <= 1) score += 1.65;
        else if (freq == 2) score += 0.35;
        else if (freq >= 4) score -= 1.25;

        if ("no".equals(asistio)) score += 1.45;
        if (atrasos >= 2) score += 1.80;
        else if (atrasos == 1) score += 0.65;

        if (satisfaccion <= 4) score += 1.75;
        else if (satisfaccion <= 6) score += 0.55;
        else if (satisfaccion >= 8) score -= 1.10;

        if ("mensual".equals(plan)) score += 0.55;
        else if ("anual".equals(plan)) score -= 0.90;

        if ("si".equals(entrenador)) score -= 0.60;
        if ("si".equals(clases)) score -= 0.50;
        if ("si".equals(lesiones)) score += 0.85;
        if ("si".equals(descuento)) score -= 0.35;
        if (meses <= 3) score += 0.45;
        else if (meses >= 12) score -= 0.55;

        double prob = Math.round((1.0 / (1.0 + Math.exp(-score))) * 10000.0) / 10000.0;
        String riesgo = prob >= 0.65 ? "ALTO" : (prob >= 0.35 ? "MEDIO" : "BAJO");
        String segmento = (freq >= 4 && satisfaccion >= 7 && atrasos == 0)
                ? "Atleta Comprometido"
                : ((freq <= 1 || atrasos >= 2 || satisfaccion <= 4) ? "Miembro en Riesgo" : "Miembro Casual");

        List<String> tips = new ArrayList<>();
        if (freq <= 2) tips.add("Programar al menos 3 sesiones semanales con recordatorios en la app FitZone.");
        if ("no".equals(asistio)) tips.add("Retomar con una clase grupal de intensidad moderada esta semana.");
        if ("no".equals(clases)) tips.add("Inscribirse en clases grupales (Spinning, Zumba o Yoga) aumenta la adherencia.");
        if ("no".equals(entrenador) && prob >= 0.45) tips.add("Solicitar una sesión de valoración con entrenador personal.");
        if (atrasos > 0) tips.add("Activar alertas de pago o evaluar un plan trimestral con descuento.");
        if ("si".equals(lesiones)) tips.add("Priorizar rutinas de bajo impacto (Pilates/movilidad) mientras te recuperas.");
        if (tips.isEmpty()) tips.add("¡Excelente constancia! Mantén tu rutina actual y registra tu progreso IMC y pasos diarios.");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("nombre", raw.getOrDefault("nombre", "Usuario FitZone"));
        res.put("correo", raw.getOrDefault("correo", ""));
        res.put("probabilidad_cancelar", prob);
        res.put("probabilidad_porcentaje", Math.round(prob * 1000.0) / 10.0);
        res.put("prediccion", prob >= 0.50 ? "SI" : "NO");
        res.put("nivel_riesgo", riesgo);
        res.put("segmento", segmento);
        res.put("recomendaciones", tips);
        return res;
    }

    private Map<String, Object> fallbackPredictBatch(List<Map<String, Object>> records) {
        List<Map<String, Object>> preds = new ArrayList<>();
        int alto = 0, medio = 0, bajo = 0;
        Map<String, Integer> segs = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            Map<String, Object> p = fallbackPredictSingle(r);
            preds.add(p);
            String risk = String.valueOf(p.get("nivel_riesgo"));
            if ("ALTO".equals(risk)) alto++;
            else if ("MEDIO".equals(risk)) medio++;
            else bajo++;
            String seg = String.valueOf(p.get("segmento"));
            segs.put(seg, segs.getOrDefault(seg, 0) + 1);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", preds.size());
        res.put("resumen_riesgo", Map.of("alto", alto, "medio", medio, "bajo", bajo));
        res.put("distribucion_segmentos", segs);
        res.put("predicciones", preds);
        return res;
    }

    private Map<String, Object> buildFallbackMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelo_ganador", "Gradient Boosting (pendiente ejecutar train_models.py)");
        m.put("dataset", Map.of("total_registros", 800, "variables_predictoras", 16));
        m.put("comparacion_modelos", List.of());
        return m;
    }

    private int toInt(Object val, int def) {
        if (val == null) return def;
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return def; }
    }

    private String toStr(Object val, String def) {
        return val == null ? def : val.toString().toLowerCase().trim();
    }
}

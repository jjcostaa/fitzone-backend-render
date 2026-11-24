package com.fitzone.fitzonev2.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitzone.fitzonev2.dto.ChatResponse;

@Service
public class ChatbotService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqApiUrl;

    @Value("${groq.model:llama3-8b-8192}")
    private String groqModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Aqui le damos las intruncciones a la IA (como el promp)
    private static final String SYSTEM_PROMPT =
            "Eres FitBot, el asistente virtual oficial de FitZone Gym. Tu rol es ayudar a los usuarios con informacion sobre el gimnasio.\n" +
            "\n" +
            "INFORMACION DEL GIMNASIO:\n" +
            "- Nombre: FitZone\n" +
            "- Descripcion: Gimnasio premium con 10 anos de experiencia, instalaciones certificadas y entrenadores profesionales.\n" +
            "- Direccion: 123 Street, New York, USA\n" +
            "- Telefono: +012 345 67890\n" +
            "- Email: fitzonegym.oficial@gmail.com\n" +
            "\n" +
            "HORARIOS DEL GIMNASIO:\n" +
            "- Lunes a Viernes: 6:00am - 10:00pm\n" +
            "- Sabados: 7:00am - 8:00pm\n" +
            "- Domingos: 8:00am - 6:00pm\n" +
            "\n" +
            "CLASES DISPONIBLES Y HORARIOS:\n" +
            "- Cardio: Lunes 6-8am, Jueves 10am-12pm, Viernes 5-7pm, Domingo 6-8am, Martes 7-9pm\n" +
            "- Crossfit: Miercoles 6-8am, Lunes 5-7pm, Sabado 6-8am, Miercoles 7-9pm, Sabado 5-7pm, Domingo 7-9pm\n" +
            "- Levantamiento de Pesas / Power Lifting: Viernes 6-8am, Martes 10am-12pm, Miercoles 5-7pm, Sabado 7-9pm\n" +
            "\n" +
            "PLANES DE MEMBRESIA:\n" +
            "- Plan Basico: Acceso a sala de fitness. Precio 50.000 COP/mes.\n" +
            "- Plan Premium: Acceso completo + clases ilimitadas + sesiones con entrenador. Precio 100.000 COP/mes.\n" +
            "- Plan Elite: Acceso VIP ilimitado 24/7 + plan nutricional + 4 sesiones entrenador/mes. Precio 150.000 COP/mes.\n" +
            "- Plan Anual: 20% de descuento pagando el ano completo.\n" +
            "\n" +
            "INSTRUCCIONES:\n" +
            "- Responde SIEMPRE en espanol.\n" +
            "- Se amigable, motivador y profesional.\n" +
            "- Si no sabes algo especifico, sugiere contactar al gimnasio directamente.\n" +
            "- Respuestas concisas (maximo 3-4 oraciones).\n" +
            "- Usa emojis ocasionalmente para ser mas amigable.\n" +
            "- No inventes informacion que no este en este contexto.\n"+
            "- no hacer niguna operacion matematica.\n"+
            "- solo responde las preguntas en base a la información del gymnasio.\n";

    public ChatResponse chat(String userMessage) {
        List<String> modelsToTry = List.of(groqModel, "openai/gpt-oss-20b", "qwen/qwen3.8-27b");
        String lastError = "No se pudo conectar con FitBot.";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        for (String model : modelsToTry) {
            try {
                Map<String, Object> requestMap = new LinkedHashMap<>();
                requestMap.put("model", model);
                requestMap.put("temperature", 0.7);
                requestMap.put("max_tokens", 512);

                List<Map<String, String>> messages = new ArrayList<>();

                Map<String, String> systemMsg = new LinkedHashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PROMPT);
                messages.add(systemMsg);

                Map<String, String> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                messages.add(userMsg);

                requestMap.put("messages", messages);

                String requestBody = objectMapper.writeValueAsString(requestMap);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(groqApiUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + groqApiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        String content = choices.get(0).path("message").path("content").asText();
                        if (content != null && !content.isEmpty()) {
                            // Limpiar etiquetas de razonamiento interno si el modelo las incluye
                            content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
                            if (!content.isEmpty()) {
                                return new ChatResponse(content, true);
                            }
                        }
                    }
                } else {
                    System.err.println("Groq API error (" + model + ") " + response.statusCode() + ": " + response.body());
                    lastError = "Error " + response.statusCode() + " al conectar con el asistente.";
                }
            } catch (Exception e) {
                System.err.println("ChatbotService exception (" + model + "): " + e.getMessage());
                lastError = "No fue posible conectar con el asistente en este momento.";
            }
        }
        return new ChatResponse(lastError + " Intenta de nuevo en unos segundos. 🙏", false);
    }
}

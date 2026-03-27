package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.service.OpenAiService;
import com.BossAi.bossAi.service.generation.GenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorAiServiceImpl implements DirectorAiService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final DirectorValidator validator;

    @Override
    public DirectorPlan generatePlan(GenerationContext context) {
        String prompt = buildPrompt(context);

        log.info("[DirectorAI] Prompt:\n{}", prompt);

        String rawJson = openAiService.generateDirectorPlan(prompt);

        DirectorPlan plan = parse(rawJson);

        validator.validate(plan, context);

        return plan;
    }

    private DirectorPlan parse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            String json = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            return objectMapper.readValue(json, DirectorPlan.class);

        } catch (Exception e) {
            throw new RuntimeException("[DirectorAI] Parse error", e);
        }
    }

    private String buildPrompt(GenerationContext context) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are a professional TikTok video editor and director.
                
                Your job is to create a high-retention edit plan.
                The video must feel dynamic, emotional, and engaging.
                
                """);

        sb.append("STYLE:\n").append(context.getStyle()).append("\n\n");

        sb.append("NARRATION:\n")
                .append(context.getScript().narration())
                .append("\n\n");

        sb.append("SCENES:\n");

        context.getScenes().forEach(scene -> {
            sb.append("Scene ")
                    .append(scene.getIndex())
                    .append(" duration=")
                    .append(scene.getDurationMs())
                    .append("ms\n");
        });

        sb.append("""
                
                RULES:
                - No empty gaps
                - Cuts must fully cover scene duration
                - Cuts must be continuous (end = next start)
                
                STYLE RULES:
                - VIRAL_EDIT → 300–800ms cuts, aggressive pacing
                - UGC_STYLE → natural, 1–3s cuts
                - CINEMATIC → long shots, 2–4s cuts
                - HIGH_CONVERTING_AD → fast start, slower middle, strong ending
                
                        Each cut MUST include:
                        - shotType (close-up / wide / medium)
                        - cameraMovement (handheld / pan / zoom / static)
                        - energy (low / medium / high)
                
                HOOK:
                - First scene must have fastest cuts
                
                OUTPUT JSON ONLY:
                {
                   "scenes": [
                     {
                       "sceneIndex": 0,
                       "cuts": [
                         {
                           "startMs": 0,
                           "endMs": 400,
                           "shotType": "close-up",
                           "cameraMovement": "handheld",
                           "energy": "high"
                         }
                       ]
                     }
                   ]
                 }
                """);

        return sb.toString();
    }
}

package com.BossAi.bossAi.service.director;

import com.BossAi.bossAi.entity.AssetSource;
import com.BossAi.bossAi.entity.AssetStatus;
import com.BossAi.bossAi.entity.AssetType;
import com.BossAi.bossAi.entity.ProjectAsset;
import com.BossAi.bossAi.service.FalAiService;
import com.BossAi.bossAi.service.ProjectAssetService;
import com.BossAi.bossAi.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generuje assety dla warstw SceneDirective z source=generate.
 *
 * Gdy user mówi "wygeneruj film giełdy jako tło" lub "generate stock video as background",
 * UserIntentParser parsuje to do LayerDirective z source=generate i generation_prompt.
 * Ten serwis realizuje te żądania:
 *   1. Generuje image przez FalAiService
 *   2. Pobiera wygenerowany obraz
 *   3. Zapisuje jako ProjectAsset
 *   4. Zwraca listę nowo utworzonych ProjectAsset
 *
 * Wywoływany przez VideoProductionOrchestrator po parsowaniu intencji,
 * przed generowaniem EDL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayerAssetGenerator {

    private final FalAiService falAiService;
    private final ProjectAssetService projectAssetService;
    private final StorageService storageService;

    private static final String DEFAULT_IMAGE_MODEL = "fal-ai/flux/schnell";

    /**
     * Generuje assety dla wszystkich warstw SceneDirective z source=generate.
     *
     * @param projectId   ID projektu
     * @param editIntent  sparsowana intencja z scene directives
     * @return lista nowo utworzonych ProjectAsset (gotowych do użycia w EDL)
     */
    public List<ProjectAsset> generateLayerAssets(UUID projectId, UserEditIntent editIntent) {
        List<ProjectAsset> generated = new ArrayList<>();

        if (editIntent == null || !editIntent.hasSceneDirectives()) {
            return generated;
        }

        for (SceneDirective directive : editIntent.getSceneDirectives()) {
            if (directive.getLayers() == null) continue;

            for (SceneDirective.LayerDirective layer : directive.getLayers()) {
                if (!layer.isGenerate()) continue;

                try {
                    ProjectAsset asset = generateLayerAsset(
                            projectId, directive.getSceneIndex(), layer);
                    if (asset != null) {
                        generated.add(asset);
                        log.info("[LayerAssetGenerator] Generated asset for scene {} layer {} — id: {}",
                                directive.getSceneIndex(), layer.getLayerIndex(), asset.getId());
                    }
                } catch (Exception e) {
                    log.warn("[LayerAssetGenerator] Failed to generate asset for scene {} layer {}: {}",
                            directive.getSceneIndex(), layer.getLayerIndex(), e.getMessage());
                }
            }
        }

        log.info("[LayerAssetGenerator] Generated {} layer assets for project {}", generated.size(), projectId);
        return generated;
    }

    private ProjectAsset generateLayerAsset(UUID projectId, int sceneIndex,
                                            SceneDirective.LayerDirective layer) throws Exception {
        String prompt = layer.getGenerationPrompt();
        String genType = layer.getGenerationType() != null ? layer.getGenerationType() : "image";

        log.info("[LayerAssetGenerator] Generating {} for scene {} layer {} — prompt: '{}'",
                genType, sceneIndex, layer.getLayerIndex(), truncate(prompt, 80));

        if ("image".equals(genType)) {
            return generateImage(projectId, sceneIndex, layer);
        } else if ("video".equals(genType)) {
            // Video generation requires an image first (image-to-video pipeline)
            // Generate image, then optionally convert to video
            return generateImage(projectId, sceneIndex, layer);
        }

        return null;
    }

    private ProjectAsset generateImage(UUID projectId, int sceneIndex,
                                       SceneDirective.LayerDirective layer) throws Exception {

        // 1. Generate image via FalAI
        String imageUrl = falAiService.generateImage(layer.getGenerationPrompt(), DEFAULT_IMAGE_MODEL);

        // 2. Download the image
        byte[] imageBytes = downloadUrl(imageUrl);

        // 3. Create ProjectAsset
        String filename = String.format("layer_%d_scene_%d_%s.jpg",
                layer.getLayerIndex(), sceneIndex, UUID.randomUUID().toString().substring(0, 8));

        ProjectAsset asset = projectAssetService.createAsset(
                projectId, AssetType.IMAGE, AssetSource.AI_GENERATED,
                filename, "image/jpeg");

        // 4. Save to storage
        String storageKey = String.format("projects/%s/layers/%s", projectId, filename);
        storageService.save(imageBytes, storageKey);

        // 5. Mark as ready
        projectAssetService.markReady(
                asset.getId(),
                storageKey,
                (long) imageBytes.length,
                null, // no duration for images
                1080, 1920);

        // Store the generation prompt on the asset for matching later
        asset.setPrompt(layer.getGenerationPrompt());

        return asset;
    }

    private byte[] downloadUrl(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Download failed — status: " + response.statusCode());
        }
        return response.body();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

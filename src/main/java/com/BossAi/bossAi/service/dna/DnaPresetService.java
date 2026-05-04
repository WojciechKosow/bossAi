package com.BossAi.bossAi.service.dna;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads DNA preset configs from classpath (resources/dna-presets/<id>.json),
 * merges them with user-supplied overrides, and returns the final resolved config.
 *
 * Config files are cached after first load — adding a new preset only requires
 * dropping a JSON file into resources/dna-presets/, no Java changes needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DnaPresetService {

    private final ObjectMapper objectMapper;

    private final Map<DnaPreset, DnaPresetConfig> cache = new ConcurrentHashMap<>();

    /**
     * Resolves the final preset config by merging base preset defaults with user overrides.
     *
     * @param preset  which preset to load
     * @param userDna user-supplied overrides (may be null → returns base preset unchanged)
     * @return merged DnaPresetConfig ready for EdlGeneratorService
     */
    public DnaPresetConfig resolve(DnaPreset preset, UserDnaInput userDna) {
        DnaPresetConfig base = load(preset);
        if (userDna == null) {
            return base;
        }
        return applyOverrides(deepCopy(base), userDna);
    }

    /**
     * Loads (and caches) the preset config from classpath JSON.
     * Safe to call repeatedly — returns the cached instance after first load.
     */
    public DnaPresetConfig load(DnaPreset preset) {
        return cache.computeIfAbsent(preset, this::readFromClasspath);
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private DnaPresetConfig readFromClasspath(DnaPreset preset) {
        String path = "dna-presets/" + preset.name().toLowerCase() + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("DNA preset config not found on classpath: " + path);
            }
            DnaPresetConfig config = objectMapper.readValue(is, DnaPresetConfig.class);
            log.info("Loaded DNA preset config: {} ({})", preset, config.getDisplayName());
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse DNA preset config: " + path, e);
        }
    }

    /** Deep copy via JSON round-trip so we never mutate the cached instance. */
    private DnaPresetConfig deepCopy(DnaPresetConfig config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            return objectMapper.readValue(json, DnaPresetConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deep-copy DnaPresetConfig", e);
        }
    }

    private DnaPresetConfig applyOverrides(DnaPresetConfig merged, UserDnaInput userDna) {
        if (userDna.getPacingOverride() != null) {
            merged.setPacing(userDna.getPacingOverride());
        }

        if (userDna.getColorGradeOverride() != null) {
            merged.setColorGrade(userDna.getColorGradeOverride());
        }

        if (userDna.getFontFamilyOverride() != null) {
            applyFontFamilyOverride(merged, userDna.getFontFamilyOverride());
        }

        // Full subtitle config override wins over per-field font override
        if (userDna.getSubtitleConfigOverride() != null) {
            merged.setSubtitleConfig(userDna.getSubtitleConfigOverride());
        }

        if (merged.getAudioConfig() != null) {
            if (userDna.getVolumeByBeatOverride() != null) {
                merged.getAudioConfig().setVolumeByBeat(userDna.getVolumeByBeatOverride());
            }
            if (userDna.getMusicStyleOverride() != null) {
                merged.getAudioConfig().setMusicStyle(userDna.getMusicStyleOverride());
            }
        }

        // textPlaceholderOverrides and bpmOverride are passed through to downstream services;
        // DnaPresetService doesn't resolve them — TextOverlayGeneratorService (Step 6) handles placeholders,
        // and MusicAlignmentService handles BPM.

        return merged;
    }

    private void applyFontFamilyOverride(DnaPresetConfig config, String fontFamily) {
        if (config.getSubtitleConfig() != null) {
            config.getSubtitleConfig().setFontFamily(fontFamily);
        }
        if (config.getTextOverlayTemplates() != null) {
            config.getTextOverlayTemplates().forEach(template -> {
                if (template.getStyle() != null) {
                    template.getStyle().setFontFamily(fontFamily);
                }
            });
        }
    }
}

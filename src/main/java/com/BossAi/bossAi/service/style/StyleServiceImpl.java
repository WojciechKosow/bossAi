package com.BossAi.bossAi.service.style;

import com.BossAi.bossAi.entity.VideoStyle;
import org.springframework.stereotype.Service;

@Service
public class StyleServiceImpl implements StyleService {


    @Override
    public StyleConfig getConfig(VideoStyle style) {

        if (style == null) {
            return highConverting(); //default style
        }

        return switch (style) {
            case VIRAL_EDIT -> viral();
            case HIGH_CONVERTING_AD -> highConverting();
            case UGC_STYLE -> ugc();
            case LUXURY_AD -> luxury();
            case CINEMATIC -> cinematic();
            case STORY_MODE -> story();
            case PRODUCT_SHOWCASE -> product();
            case EDUCATIONAL -> educational();
            case CUSTOM -> highConverting(); // fallback for now (then I'll replace it with custom)
        };
    }

    @Override
    public StyleConfig viral() {
        return StyleConfig.builder()
                .energyLevel("HIGH")
                .pacing("FAST")
                .promptInstructions("""
                    STYLE: VIRAL TIKTOK EDIT
                    
                    - Extremely fast pacing (cuts every 0.5–1s)
                    - Aggressive hooks, pattern interrupts
                    - Dynamic camera movement: zooms, whip pans, shakes
                    - High contrast, bold visuals
                    - Content must feel chaotic, addictive, scroll-stopping
                    - Scenes must show action, NOT static shots
                    """)
                .build();
    }

    @Override
    public StyleConfig highConverting() {
        return StyleConfig.builder()
                .energyLevel("MEDIUM")
                .pacing("MEDIUM")
                .promptInstructions("""
                    STYLE: HIGH CONVERTING ECOM AD
                    
                    - Structure: HOOK → PROBLEM → SOLUTION → CTA
                    - Focus on product benefits and clarity
                    - Clear messaging, easy to understand
                    - Show product in use
                    - Emphasize pain points and transformation
                    """)
                .build();
    }

    @Override
    public StyleConfig ugc() {
        return StyleConfig.builder()
                .energyLevel("MEDIUM")
                .pacing("FAST")
                .promptInstructions("""
                    STYLE: UGC / INFLUENCER
                    
                    - Looks like recorded on phone (iPhone style)
                    - Natural, imperfect, authentic
                    - Casual language, relatable tone
                    - Jump cuts, vlog-style delivery
                    - Feels like real user recommendation
                    """)
                .build();
    }

    @Override
    public StyleConfig luxury() {
        return StyleConfig.builder()
                .energyLevel("LOW")
                .pacing("SLOW")
                .promptInstructions("""
                    STYLE: LUXURY BRAND
                    
                    - Slow, smooth cinematic shots
                    - Minimalism, elegance
                    - Soft lighting, premium feel
                    - Focus on aesthetics and emotion
                    - No aggressive cuts or chaotic motion
                    """)
                .build();
    }

    @Override
    public StyleConfig cinematic() {
        return StyleConfig.builder()
                .energyLevel("MEDIUM")
                .pacing("SLOW")
                .promptInstructions("""
                    STYLE: CINEMATIC
                    
                    - Film-like storytelling
                    - Dramatic lighting and composition
                    - Smooth camera movement
                    - Emotional tone
                    - Strong visual storytelling
                    """)
                .build();
    }

    @Override
    public StyleConfig story() {
        return StyleConfig.builder()
                .energyLevel("MEDIUM")
                .pacing("MEDIUM")
                .promptInstructions("""
                    STYLE: STORYTELLING
                    
                    - Narrative-driven structure
                    - Build tension and curiosity
                    - Each scene must progress the story
                    - Emotional engagement is key
                    """)
                .build();
    }

    @Override
    public StyleConfig product() {
        return StyleConfig.builder()
                .energyLevel("LOW")
                .pacing("MEDIUM")
                .promptInstructions("""
                    STYLE: PRODUCT SHOWCASE
                    
                    - Focus on product details
                    - Close-ups and feature highlights
                    - Clean background or controlled environment
                    - Show product clearly and professionally
                    """)
                .build();
    }

    @Override
    public StyleConfig educational() {
        return StyleConfig.builder()
                .energyLevel("MEDIUM")
                .pacing("MEDIUM")
                .promptInstructions("""
                    STYLE: EDUCATIONAL

                    - Clear and informative
                    - Value-driven content
                    - Quick explanations
                    - Text-heavy and structured
                    - Focus on teaching something useful
                    """)
                .build();
    }
}

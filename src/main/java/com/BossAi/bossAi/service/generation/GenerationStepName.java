package com.BossAi.bossAi.service.generation;

/**
 * Pipeline step names — used in GenerationContext.currentStep
 * and by ProgressService for the SSE stream to the frontend.
 *
 * The order reflects the actual execution sequence:
 *
 *   SCRIPT → IMAGE → [VOICE + VIDEO in parallel] → MUSIC → RENDER → DONE
 */
public enum GenerationStepName {

    /** Initialization — request validation, credit reservation */
    INITIALIZING("Preparing generation...", 5),

    /** GPT-4o generates the script JSON */
    SCRIPT("Generating ad script...", 15),

    /** fal.ai generates images per scene */
    IMAGE("Generating scene images...", 30),

    /** OpenAI TTS generates the voice-over from the narration */
    VOICE("Generating voice-over...", 50),

    /** fal.ai animates the images (Kling O1) */
    VIDEO("Generating scene videos...", 70),

    /** Downloading/copying the user's music file */
    MUSIC("Preparing music...", 80),

    /** FFmpeg assembles all assets into the final MP4 */
    RENDER("Editing the final video...", 90),

    /** Save to storage, save assets to the database */
    SAVING("Saving results...", 97),

    /** Pipeline finished successfully */
    DONE("Done! Your ad is ready.", 100),

    /** Pipeline finished with an error */
    FAILED("Generation failed.", 0);

    private final String displayMessage;
    private final int progressPercent;

    GenerationStepName(String displayMessage, int progressPercent) {
        this.displayMessage = displayMessage;
        this.progressPercent = progressPercent;
    }

    public String getDisplayMessage() {
        return displayMessage;
    }

    public int getProgressPercent() {
        return progressPercent;
    }
}

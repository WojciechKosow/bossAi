package com.BossAi.bossAi.service.generation;

/**
 * Nazwy kroków pipeline — używane w GenerationContext.currentStep
 * oraz przez ProgressService do SSE stream dla frontendu.
 *
 * Kolejność odzwierciedla faktyczną sekwencję wykonania:
 *
 *   SCRIPT → IMAGE → [VOICE + VIDEO równolegle] → MUSIC → RENDER → DONE
 */
public enum GenerationStepName {

    /** Inicjalizacja — walidacja requestu, rezerwacja kredytów */
    INITIALIZING("Przygotowuję generację...", 5),

    /** GPT-4o generuje scenariusz JSON */
    SCRIPT("Generuję scenariusz reklamy...", 15),

    /** fal.ai generuje obrazy per scena */
    IMAGE("Generuję obrazy scen...", 30),

    /** OpenAI TTS generuje voice-over z narracji */
    VOICE("Generuję voice-over...", 50),

    /** fal.ai animuje obrazy (Kling O1) */
    VIDEO("Generuję wideo scen...", 70),

    /** Pobieranie/kopiowanie pliku muzycznego usera */
    MUSIC("Przygotowuję muzykę...", 80),

    /** FFmpeg scala wszystkie assety w finalny MP4 */
    RENDER("Montuję finalny film...", 90),

    /** Zapis do storage, zapis assetów do bazy */
    SAVING("Zapisuję wyniki...", 97),

    /** Pipeline zakończony sukcesem */
    DONE("Gotowe! Twoja reklama jest gotowa.", 100),

    /** Pipeline zakończony błędem */
    FAILED("Generacja nieudana.", 0);

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
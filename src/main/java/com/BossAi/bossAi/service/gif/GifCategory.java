package com.BossAi.bossAi.service.gif;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Categories of GIFs autonomously overlaid on videos.
 *
 * Each category has:
 *   - key:         an identifier (used in configuration and logs)
 *   - searchQuery: zapytanie do Giphy Stickers API
 *   - defaultPosition: the default on-screen position
 *   - defaultScale:    the default scale (0.0-1.0)
 */
@Getter
@RequiredArgsConstructor
public enum GifCategory {

    SUBSCRIBE_BUTTON(
            "subscribe",
            "subscribe button tiktok",
            "bottom_center",
            0.55
    ),
    FOLLOW_BUTTON(
            "follow",
            "follow button social media",
            "bottom_center",
            0.50
    ),
    LIKE_HEART(
            "like",
            "like heart animation",
            "bottom_right",
            0.30
    ),
    FIRE(
            "fire",
            "fire emoji sticker",
            "top_right",
            0.25
    ),
    SWIPE_UP(
            "swipe_up",
            "swipe up arrow gesture",
            "bottom_center",
            0.40
    );

    private final String key;
    private final String searchQuery;
    private final String defaultPosition;
    private final double defaultScale;
}

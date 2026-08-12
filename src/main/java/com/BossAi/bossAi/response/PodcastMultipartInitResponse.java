package com.BossAi.bossAi.response;

import java.util.List;

/**
 * Response for {@code POST /api/podcast/upload-multipart/init}.
 *
 * @param storageKey the object key the assembled file will live at
 * @param uploadId   the R2 multipart upload id (echoed back on complete)
 * @param parts      one presigned PUT URL per part, in part-number order
 */
public record PodcastMultipartInitResponse(
        String storageKey,
        String uploadId,
        List<PartUrl> parts
) {
    /** A single part's upload target: its 1-based number and presigned PUT URL. */
    public record PartUrl(int partNumber, String url) {
    }
}

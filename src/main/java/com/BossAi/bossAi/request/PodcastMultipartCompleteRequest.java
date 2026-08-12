package com.BossAi.bossAi.request;

import java.util.List;

/**
 * Request to finalize a multipart direct-to-R2 upload: the client sends back the
 * ETag R2 returned for each uploaded part so the object can be assembled.
 *
 * @param storageKey the object key returned by init
 * @param uploadId   the multipart upload id returned by init
 * @param parts      every uploaded part's number and ETag
 */
public record PodcastMultipartCompleteRequest(
        String storageKey,
        String uploadId,
        List<Part> parts
) {
    /** One finished part: its 1-based number and the ETag R2 returned on PUT. */
    public record Part(int partNumber, String etag) {
    }
}

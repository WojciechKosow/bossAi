package com.BossAi.bossAi.request;

/**
 * Request to begin a multipart direct-to-R2 upload for a large episode
 * (&gt; R2's 5 GiB single-PUT limit).
 *
 * @param filename  original filename, used to derive the object-key extension
 * @param partCount number of parts the client will upload (it slices the file
 *                  into this many chunks); the backend returns one presigned PUT
 *                  URL per part
 */
public record PodcastMultipartInitRequest(String filename, Integer partCount) {
}

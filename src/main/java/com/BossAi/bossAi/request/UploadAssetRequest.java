package com.BossAi.bossAi.request;

import com.BossAi.bossAi.entity.AssetType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadAssetRequest {
    private AssetType type;
    private MultipartFile file;
}

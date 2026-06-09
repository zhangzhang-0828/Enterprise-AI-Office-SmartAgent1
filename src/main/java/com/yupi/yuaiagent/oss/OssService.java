package com.yupi.yuaiagent.oss;

import com.yupi.yuaiagent.config.OssConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 对象存储上传服务
 *
 * <p>职责：把任意字节流上传到对象存储，返回可公开访问的 URL。
 * <p>使用场景：PDFGenerationTool 生成 PDF 字节流 → upload() → 返回下载链接给前端。
 */
@Slf4j
@Service
public class OssService {

    private final OssConfig ossConfig;
    /** 懒构建：仅在 enabled=true 时才真正 new MinioClient，false 时保持 null */
    private MinioClient minioClient;

    public OssService(OssConfig ossConfig) {
        this.ossConfig = ossConfig;
    }

    /**
     * 启动时确保 bucket 存在 + 设置为 public-read
     * （public-read 让生成的 URL 可以无需鉴权直接访问）
     */
    @PostConstruct
    public void initBucket() {
        if (!ossConfig.isEnabled()) {
            log.info("[OSS] 未启用对象存储，PDF 工具将回退到本地保存");
            return;
        }
        try {
            // 在 init 阶段才真正构造 MinioClient，避免 OSS 关闭时 Bean 被 null 注入报错
            MinioClient.Builder builder = MinioClient.builder()
                    .endpoint(ossConfig.getEndpoint())
                    .credentials(ossConfig.getAccessKey(), ossConfig.getSecretKey());
            if (ossConfig.getRegion() != null && !ossConfig.getRegion().isBlank()) {
                builder.region(ossConfig.getRegion());
            }
            this.minioClient = builder.build();

            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(ossConfig.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(ossConfig.getBucket()).build());
                log.info("[OSS] 已创建 bucket: {}", ossConfig.getBucket());
            }
            // 设置匿名可读策略（read-only），这样生成的 URL 不需要预签名
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(ossConfig.getBucket());
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .config(policy)
                            .build());
            log.info("[OSS] bucket {} 已设为 public-read", ossConfig.getBucket());
        } catch (Exception e) {
            log.error("[OSS] 初始化 bucket 失败", e);
            this.minioClient = null;
        }
    }

    /**
     * 上传字节流到对象存储，返回可公开访问的 URL
     *
     * @param data        文件字节内容
     * @param originalName 原始文件名（用于确定后缀）
     * @param contentType MIME 类型（application/pdf、image/png 等）
     * @return 公开访问的 URL；失败或未启用时返回 null
     */
    public String upload(byte[] data, String originalName, String contentType) {
        if (!isEnabled()) {
            return null;
        }
        try {
            // 按日期分目录：2025-06-30/<uuid>_<originalName>
            // 好处：避免单目录文件过多 + 便于按日期清理
            String dateDir = LocalDate.now().toString();
            String suffix = extractSuffix(originalName);
            String objectKey = "%s/%s_%s%s".formatted(
                    dateDir,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    sanitize(originalName),
                    suffix == null ? "" : suffix);

            try (InputStream inputStream = new ByteArrayInputStream(data)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(ossConfig.getBucket())
                                .object(objectKey)
                                .stream(inputStream, data.length, -1)
                                .contentType(contentType)
                                .build());
            }

            // 拼接公开 URL
            String publicUrl = buildPublicUrl(objectKey);
            log.info("[OSS] 上传成功: {} -> {}", objectKey, publicUrl);
            return publicUrl;
        } catch (Exception e) {
            log.error("[OSS] 上传失败", e);
            return null;
        }
    }

    /**
     * 拼接公开 URL：publicBaseUrl + "/" + objectKey
     */
    private String buildPublicUrl(String objectKey) {
        String base = ossConfig.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            // 没配 publicBaseUrl 时，从 endpoint + bucket 拼一个 path-style URL
            return ossConfig.getEndpoint() + "/" + ossConfig.getBucket() + "/" + objectKey;
        }
        return base.endsWith("/") ? base + objectKey : base + "/" + objectKey;
    }

    private String extractSuffix(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : null;
    }

    /** 把文件名里的危险字符替换成下划线，避免 key 非法 */
    private String sanitize(String name) {
        if (name == null) return "file";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    public boolean isEnabled() {
        return ossConfig.isEnabled() && minioClient != null;
    }
}

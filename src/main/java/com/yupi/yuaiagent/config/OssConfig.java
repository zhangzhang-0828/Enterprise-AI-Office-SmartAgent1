package com.yupi.yuaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储（MinIO / S3 兼容）配置
 *
 * <p>支持任何兼容 S3 协议的对象存储：
 * <ul>
 *   <li>本地 MinIO：endpoint=http://localhost:9000，pathStyleAccess=true</li>
 *   <li>阿里云 OSS：endpoint=https://oss-cn-hangzhou.aliyuncs.com，pathStyleAccess=false</li>
 *   <li>腾讯云 COS / AWS S3：同理，关闭 path-style，开启 virtual-host-style</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * oss:
 *   enabled: true
 *   endpoint: http://localhost:9000
 *   access-key: minioadmin
 *   secret-key: minioadmin
 *   bucket: yu-ai-agent
 *   public-base-url: http://localhost:9000/yu-ai-agent
 *   path-style-access: true
 * </pre>
 *
 * <p>MinioClient 实例不在此处声明为 Bean，
 * 而是在 {@link com.yupi.yuaiagent.oss.OssService#initBucket()} 中按需懒构造，
 * 避免 enabled=false 时 null Bean 导致注入失败。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oss")
public class OssConfig {

    /** 是否启用对象存储；false 时 PDFGenerationTool 回退到本地保存 */
    private boolean enabled = false;

    /** S3 兼容服务地址（MinIO / 阿里云 OSS endpoint） */
    private String endpoint;

    /** AccessKey（MinIO 默认 minioadmin） */
    private String accessKey;

    /** SecretKey（MinIO 默认 minioadmin） */
    private String secretKey;

    /** 存储桶名（需提前创建） */
    private String bucket;

    /**
     * 公开访问的基础 URL，用于拼接最终的可访问文件 URL
     * 例如：http://localhost:9000/yu-ai-agent
     *      https://my-bucket.oss-cn-hangzhou.aliyuncs.com
     */
    private String publicBaseUrl;

    /**
     * path-style 访问：true 走 http://endpoint/bucket/key 形式（MinIO 默认）
     *                 false 走 http://bucket.endpoint/key 形式（阿里云 / AWS 推荐）
     */
    private boolean pathStyleAccess = true;

    /**
     * region：MinIO 可填任意值，阿里云 / AWS 必填对应 region
     */
    private String region = "us-east-1";
}

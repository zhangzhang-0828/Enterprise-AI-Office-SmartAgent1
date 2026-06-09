package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.oss.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * PDF 生成工具
 *
 * <p>支持两种保存方式（通过 {@code oss.enabled} 配置切换）：
 * <ul>
 *   <li>对象存储（推荐）：PDF 生成后直接上传到 MinIO / 阿里云 OSS，返回可公开访问的 URL</li>
 *   <li>本地保存（兜底）：保存到项目目录的 tmp/pdf/，返回本地路径</li>
 * </ul>
 *
 * <p>关键设计：{@code returnDirect = true}，让 PDF URL 作为最终回复直接返回给用户，
 * 避免 LLM 再加工一次（节省一次 LLM 调用 + 减少幻觉）。
 */
@Slf4j
public class PDFGenerationTool {

    private static final List<String> CANDIDATE_FONT_PATHS = List.of(
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simsun.ttf",
            "C:/Windows/Fonts/simhei.ttf"
    );

    private final OssService ossService;

    public PDFGenerationTool(OssService ossService) {
        this.ossService = ossService;
    }

    /**
     * 生成 PDF 并返回可下载的 URL / 本地路径
     *
     * <p>returnDirect = true：返回值直接当作最终回复给用户，不再让 LLM 加工。
     * 原因：PDF URL 已经是用户能直接消费的"成品"，让 LLM 再总结一次既浪费 token，
     *      也容易出现"AI 总结错了"或"AI 重复描述 URL 内容"等幻觉。
     */
    @Tool(description = "Generate a PDF file with given content and return a downloadable URL. " +
            "Use this when the user wants a downloadable document, report, or exported file. " +
            "Returns the file URL directly without further AI processing.",
            returnDirect = true)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF, e.g. 'love-report.pdf'") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        // 文件名兜底，防止 LLM 没传 fileName
        if (fileName == null || fileName.isBlank()) {
            fileName = "document-" + System.currentTimeMillis() + ".pdf";
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        try {
            // Step 1：把 PDF 生成到内存字节数组（不落盘）
            byte[] pdfBytes = renderPdfToBytes(content);

            // Step 2：优先上传到对象存储，返回可访问 URL
            if (ossService != null && ossService.isEnabled()) {
                String publicUrl = ossService.upload(pdfBytes, fileName, "application/pdf");
                if (publicUrl != null) {
                    log.info("PDF generated and uploaded: {}", publicUrl);
                    return "PDF 已生成，可下载访问：" + publicUrl;
                }
                log.warn("OSS upload failed, fallback to local save");
            }

            // Step 3：OSS 未启用或上传失败时，回退到本地保存
            return saveToLocal(fileName, pdfBytes);
        } catch (Exception e) {
            log.error("generate pdf failed", e);
            return "Error generating PDF: " + e.getMessage();
        }
    }

    /**
     * 把 PDF 渲染到内存中的字节数组（流式生成，不落盘）
     */
    private byte[] renderPdfToBytes(String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(baos);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            document.setFont(loadChineseFont());
            document.add(new Paragraph(content));
        }
        return baos.toByteArray();
    }

    /**
     * 回退方案：把字节数组落盘到本地 tmp/pdf/，返回本地绝对路径
     */
    private String saveToLocal(String fileName, byte[] pdfBytes) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            FileUtil.mkdir(fileDir);
            FileUtil.writeBytes(pdfBytes, filePath);
            log.info("PDF saved locally: {}", filePath);
            return "PDF 已生成（本地路径）：" + filePath;
        } catch (Exception e) {
            log.error("save pdf to local failed", e);
            return "Error saving PDF: " + e.getMessage();
        }
    }

    private PdfFont loadChineseFont() throws IOException {
        for (String fontPath : CANDIDATE_FONT_PATHS) {
            if (FileUtil.exist(fontPath)) {
                try {
                    return PdfFontFactory.createFont(fontPath, PdfEncodings.IDENTITY_H);
                } catch (Exception e) {
                    log.warn("failed to load font from path: {}", fontPath, e);
                }
            }
        }
        try {
            return PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
        } catch (Exception e) {
            log.warn("failed to load built-in Chinese font", e);
            return PdfFontFactory.createFont();
        }
    }
}

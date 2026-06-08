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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.List;

/**
 * PDF 生成工具
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

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            FileUtil.mkdir(fileDir);
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                document.setFont(loadChineseFont());
                document.add(new Paragraph(content));
            }
            return "PDF generated successfully to: " + filePath;
        } catch (IOException e) {
            log.error("generate pdf failed", e);
            return "Error generating PDF: " + e.getMessage();
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

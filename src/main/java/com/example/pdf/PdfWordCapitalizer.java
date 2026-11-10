package com.example.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Capitalizes the first letter of every word in a PDF, in place.
 *
 * <p>The text is changed directly inside the page content streams, so the
 * layout, fonts, images and positions of the original PDF are kept. The input
 * file is overwritten and the same {@link File} is returned.
 *
 * <p>Usage: {@code java -jar pdf-capitalizer-1.0.0.jar input.pdf}
 */
public final class PdfWordCapitalizer {

    /** TJ spacing larger than this (in 1/1000 em) is treated as a gap between words. */
    private static final float WORD_GAP = 200f;

    private boolean atWordStart = true;
    private boolean fontEditable = true;
    private boolean skippedComplexFont;
    private int lettersChanged;
    private final Set<COSBase> visitedForms = new HashSet<>();

    private PdfWordCapitalizer() {
    }

    /**
     * Capitalizes every word in {@code pdf}, writes the result back to the same
     * file and returns that file.
     */
    public static File capitalizeWords(File pdf) throws IOException {
        Path path = pdf.toPath().toAbsolutePath();
        // Load from bytes so the original file is not locked while we overwrite it.
        byte[] original = Files.readAllBytes(path);

        PdfWordCapitalizer capitalizer = new PdfWordCapitalizer();
        try (PDDocument doc = Loader.loadPDF(original)) {
            for (PDPage page : doc.getPages()) {
                capitalizer.atWordStart = true;
                capitalizer.fontEditable = true;
                List<Object> tokens = new PDFStreamParser(page).parse();
                capitalizer.process(tokens, page.getResources());

                PDStream newContents = new PDStream(doc);
                writeTokens(newContents, tokens);
                page.setContents(newContents);
            }

            // Save to a temp file next to the original, then replace it in one step,
            // so a failure half-way never leaves a corrupted PDF behind.
            Path tmp = Files.createTempFile(path.getParent(), "capitalized-", ".pdf");
            try {
                doc.save(tmp.toFile());
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        System.out.println("Letters capitalized: " + capitalizer.lettersChanged);
        if (capitalizer.skippedComplexFont) {
            System.out.println("Note: some text uses multi-byte (Type0/CID) fonts and was left unchanged.");
        }
        return pdf;
    }

    private void process(List<Object> tokens, PDResources resources) throws IOException {
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            Object prev = i > 0 ? tokens.get(i - 1) : null;

            switch (op.getName()) {
                // Font change: "/F1 12 Tf" -> font name is two tokens back.
                case "Tf" -> fontEditable = isEditableFont(resources, i >= 2 ? tokens.get(i - 2) : null);

                // Start of text block / moving to a new line starts a new word.
                case "BT", "Td", "TD", "T*", "Tm" -> atWordStart = true;

                case "Tj" -> {
                    if (prev instanceof COSString s) {
                        capitalize(s);
                    }
                }
                // ' and " move to the next line, then show text.
                case "'", "\"" -> {
                    atWordStart = true;
                    if (prev instanceof COSString s) {
                        capitalize(s);
                    }
                }
                case "TJ" -> {
                    if (prev instanceof COSArray array) {
                        for (COSBase item : array) {
                            if (item instanceof COSString s) {
                                capitalize(s);
                            } else if (item instanceof COSNumber n && n.floatValue() < -WORD_GAP) {
                                atWordStart = true; // large positive shift = visual space
                            }
                        }
                    }
                }
                // Text inside reusable form XObjects (common in generated PDFs).
                case "Do" -> {
                    if (prev instanceof COSName name && resources != null) {
                        PDXObject xobject = resources.getXObject(name);
                        if (xobject instanceof PDFormXObject form) {
                            processForm(form, resources);
                        }
                    }
                }
                default -> {
                    // other operators don't affect text
                }
            }
        }
    }

    private void processForm(PDFormXObject form, PDResources parentResources) throws IOException {
        if (!visitedForms.add(form.getCOSObject())) {
            return; // already handled (forms can be shared between pages)
        }
        PDResources formResources = form.getResources() != null ? form.getResources() : parentResources;

        boolean savedEditable = fontEditable;
        List<Object> tokens = new PDFStreamParser(form).parse();
        process(tokens, formResources);
        writeTokens(form.getContentStream(), tokens);
        fontEditable = savedEditable;
    }

    /**
     * Only simple (single-byte) fonts can be edited byte-by-byte. Type0/CID fonts
     * store glyph IDs, not characters, so changing bytes there would print wrong glyphs.
     */
    private boolean isEditableFont(PDResources resources, Object fontNameToken) {
        if (resources == null || !(fontNameToken instanceof COSName fontName)) {
            return true;
        }
        try {
            PDFont font = resources.getFont(fontName);
            if (font instanceof PDType0Font) {
                skippedComplexFont = true;
                return false;
            }
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    /** Uppercases a-z when it is the first letter of a word. */
    private void capitalize(COSString text) {
        byte[] bytes = text.getBytes();
        if (!fontEditable) {
            if (bytes.length > 0) {
                atWordStart = false;
            }
            return;
        }

        boolean modified = false;
        for (int k = 0; k < bytes.length; k++) {
            int c = bytes[k] & 0xFF;
            if (atWordStart && c >= 'a' && c <= 'z') {
                bytes[k] = (byte) (c - 32);
                modified = true;
                lettersChanged++;
            }
            atWordStart = !isWordChar(c);
        }
        if (modified) {
            text.setValue(bytes);
        }
    }

    /** Letters, digits and apostrophes belong to a word ("don't" stays "Don't"). */
    private static boolean isWordChar(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '\''
                || c == 0x92   // right single quote in WinAnsiEncoding
                || c >= 0xC0;  // accented letters in WinAnsi/Latin-1
    }

    private static void writeTokens(PDStream stream, List<Object> tokens) throws IOException {
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java -jar pdf-capitalizer-1.0.0.jar <file.pdf>");
            System.exit(1);
        }
        File input = new File(args[0]);
        if (!input.isFile()) {
            System.err.println("File not found: " + input.getAbsolutePath());
            System.exit(1);
        }
        File result = capitalizeWords(input);
        System.out.println("Updated PDF: " + result.getAbsolutePath());
    }
}

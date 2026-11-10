# PDF Word Capitalizer

A small Java tool that **capitalizes the first letter of every word in a PDF** and writes the result **back into the same PDF file**.

```
Before:  hello world, this is page 1.
After:   Hello World, This Is Page 1.
```

The text is edited directly inside the PDF's page content, so the **layout, fonts, images and positions stay the same**. The tool does not extract the text and rebuild the document.

Built with [Apache PDFBox 3](https://pdfbox.apache.org/).

---

## Features

- Capitalizes the first letter of every word (`hello world` → `Hello World`)
- Edits the PDF **in place** and returns the same file
- Keeps the original layout, fonts and formatting
- Handles all pages, words split across text pieces (kerning), line breaks, and text inside reusable form objects
- Handles apostrophes correctly: `don't` → `Don't` (not `Don'T`)
- Leaves words that are already capitalized unchanged
- Writes safely: saves to a temp file first, then replaces the original, so a failure never corrupts your PDF

## Requirements

- Java 17 or newer
- Maven 3.6 or newer

## Build

```bash
mvn package
```

This creates a single runnable jar: `target/pdf-capitalizer-1.0.0.jar`.

## Usage

### From the command line

```bash
java -jar target/pdf-capitalizer-1.0.0.jar path/to/your.pdf
```

Output:

```
Letters capitalized: 28
Updated PDF: C:\path\to\your.pdf
```

> ⚠️ The input file is **overwritten**. Keep a copy if you need the original.

### From Java code

```java
import com.example.pdf.PdfWordCapitalizer;
import java.io.File;

File updated = PdfWordCapitalizer.capitalizeWords(new File("input.pdf"));
// 'updated' is the same file, now with every word capitalized
```

## How it works

1. Reads the PDF into memory with PDFBox.
2. Parses each page's content stream into tokens (text operators such as `Tj`, `TJ`, `'`, `"`).
3. Walks through the text strings and keeps track of word boundaries across pieces and lines. A line break or a large gap between pieces counts as a word boundary.
4. Turns the first lowercase letter of each word into uppercase.
5. Writes the updated content back to the page, saves to a temp file, and replaces the original.

## Limitations

| Case | What happens |
|------|--------------|
| **Multi-byte fonts** (Type0/CID, common in PDFs exported from Word or browsers) | That text is skipped, because the bytes store glyph IDs, not letters. The tool prints a note. |
| **Subset fonts** (only the used glyphs are embedded) | If an uppercase letter was never used in the original, the font may not contain it, and it can show blank. |
| **Scanned PDFs** (pages are images) | There is no real text to edit. Run OCR first. |
| **Non-Latin scripts** | Only `a`–`z` is changed to uppercase. |

## Project structure

```
pdf-capitalizer/
├── pom.xml
├── README.md
└── src/main/java/com/example/pdf/
    └── PdfWordCapitalizer.java
```

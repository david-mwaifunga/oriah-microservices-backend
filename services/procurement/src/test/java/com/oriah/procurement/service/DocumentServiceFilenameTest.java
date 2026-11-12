package com.oriah.procurement.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.procurement.model.DocumentType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name a person sees for an uploaded file. It comes from the uploader's computer, so it is
 * untrusted: only its last part is kept, nothing that could break out of a header or a path
 * survives, and the extension always matches what the file really is.
 */
class DocumentServiceFilenameTest {

    @Test
    @DisplayName("a good name is left alone, spaces and capitals included")
    void goodNames() {
        assertThat(DocumentService.tidyFilename("Fycargo Invoice 12 Nov.pdf", DocumentType.PDF)).isEqualTo("Fycargo Invoice 12 Nov.pdf");
        assertThat(DocumentService.tidyFilename("scan.JPEG", DocumentType.JPEG)).isEqualTo("scan.JPEG");
    }

    @Test
    @DisplayName("only the last part of a path is kept, whichever slash it used")
    void pathsAreDropped() {
        assertThat(DocumentService.tidyFilename("../../etc/passwd.pdf", DocumentType.PDF)).isEqualTo("passwd.pdf");
        assertThat(DocumentService.tidyFilename("C:\\Users\\x\\Desktop\\bill.pdf", DocumentType.PDF)).isEqualTo("bill.pdf");
        assertThat(DocumentService.tidyFilename("/var/tmp/", DocumentType.PDF)).isEqualTo("document.pdf");
    }

    @Test
    @DisplayName("quotes and control characters are removed, so the name cannot break out of a header")
    void dangerousCharactersAreRemoved() {
        assertThat(DocumentService.tidyFilename("in\"voice\r\nSet-Cookie: x=1.pdf", DocumentType.PDF))
                .isEqualTo("invoiceSet-Cookie: x=1.pdf");
        assertThat(DocumentService.tidyFilename("a\u0000b\u007f.pdf", DocumentType.PDF)).isEqualTo("ab.pdf");
    }

    @Test
    @DisplayName("an extension that does not fit the file is not trusted: the right one is added")
    void extensionsFollowTheFile() {
        assertThat(DocumentService.tidyFilename("scan.pdf", DocumentType.PNG)).isEqualTo("scan.pdf.png");
        assertThat(DocumentService.tidyFilename("photo", DocumentType.JPEG)).isEqualTo("photo.jpg");
        assertThat(DocumentService.tidyFilename("invoice.exe", DocumentType.PDF)).isEqualTo("invoice.exe.pdf");
    }

    @Test
    @DisplayName("nothing left, or only dots, becomes 'document'")
    void emptyNames() {
        assertThat(DocumentService.tidyFilename(null, DocumentType.PDF)).isEqualTo("document.pdf");
        assertThat(DocumentService.tidyFilename("   ", DocumentType.PNG)).isEqualTo("document.png");
        assertThat(DocumentService.tidyFilename("..", DocumentType.PDF)).isEqualTo("document.pdf");
        assertThat(DocumentService.tidyFilename(".pdf", DocumentType.PDF)).isEqualTo("document.pdf");
    }

    @Test
    @DisplayName("at most 120 characters, and the extension survives the cut")
    void length() {
        String name = DocumentService.tidyFilename("x".repeat(300) + ".pdf", DocumentType.PDF);

        assertThat(name).hasSize(120).endsWith(".pdf");
        assertThat(DocumentService.tidyFilename("y".repeat(300), DocumentType.JPEG)).hasSize(120).endsWith(".jpg");
    }
}

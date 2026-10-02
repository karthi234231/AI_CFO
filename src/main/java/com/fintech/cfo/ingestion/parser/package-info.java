/**
 * The parsers: the only code in this module that turns bytes into rows.
 *
 * <p>Each implements {@link com.fintech.cfo.ingestion.parser.FileParser} and holds
 * no state between calls. Parsing is the first stage that looks at the file's
 * content, which is why it only ever runs behind
 * {@link com.fintech.cfo.ingestion.security.FileUploadSecurityService}.
 */
@NullMarked
package com.fintech.cfo.ingestion.parser;

import org.jspecify.annotations.NullMarked;
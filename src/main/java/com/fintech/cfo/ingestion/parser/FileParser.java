package com.fintech.cfo.ingestion.parser;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.model.FileParseResult;

/**
 * The port every format reader implements.
 *
 * <p>Pure by construction: a parser reads bytes and returns rows or a reason.
 * It performs no I/O beyond the stream it is handed, holds no state between
 * calls, and never touches a database. That is what makes the whole ingestion
 * path verifiable without infrastructure.
 *
 * <p>Two guarantees every implementation must honour:
 *
 * <ul>
 * <li><b>Traceability.</b> Every row it returns carries a {@link
 * com.fintech.cfo.ingestion.model.RowCoordinate} naming the file and the 1-based
 * row. A row that cannot be traced is not returned.</li>
 * <li><b>Explicit rejection.</b> Nothing is dropped quietly. A row that cannot be
 * read becomes a {@link com.fintech.cfo.ingestion.model.RejectedRow} with a
 * reason; a file that cannot be read becomes a failed result with a reason. An
 * implementation that returns fewer rows than the file contains must say why.</li>
 * </ul>
 *
 * <p>The stream in the request is consumed and closed by the call.
 */
public interface FileParser {

	/**
	 * @return the single {@link FileType} this reader handles
	 */
	FileType supportedType();

	/**
	 * @param request bytes, filename, declared content type, fixed as-of date and limits
	 * @return the rows read, the rows refused and why, and whether the whole file
	 * was seen; never {@code null} and never throwing for bad input
	 */
	FileParseResult parse(ParseRequest request);

}
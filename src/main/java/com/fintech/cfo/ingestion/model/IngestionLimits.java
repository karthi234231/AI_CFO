package com.fintech.cfo.ingestion.model;

/**
 * Hard ceilings applied to every upload before it is parsed.
 *
 * <p>These are limits, not preferences. Each one exists because violating it
 * costs the process something it cannot get back: memory for the size and
 * archive caps, auditability for the row and column caps, and CPU for the ratio
 * cap that stops a decompression bomb. Values are deliberately explicit and
 * injected so a test can lower them and prove the guard fires, rather than
 * asserting behaviour that only appears at 200&nbsp;MB.
 *
 * @param maxFileBytes          largest upload accepted at all
 * @param maxRowsPerFile        rows read from one file before truncation is reported
 * @param maxColumns            columns accepted; wider input is rejected, not silently truncated
 * @param maxSheets             workbook tabs read before the rest are reported as skipped
 * @param maxUncompressedBytes  total inflated bytes tolerated inside an OOXML container
 * @param maxArchiveEntries     zip entries tolerated inside an OOXML container
 * @param maxCompressionRatio   inflated-to-compressed ratio tolerated, applied only at or above
 *                              {@link #ratioCheckMinBytes} so tiny, legitimately dense files are not punished
 * @param ratioCheckMinBytes    smallest archive for which the ratio check is enforced
 * @param maxCellTextLength     longest single cell value accepted
 * @param headerScanWindow      rows inspected when deciding where the header is
 */
public record IngestionLimits(long maxFileBytes, int maxRowsPerFile, int maxColumns, int maxSheets,
		long maxUncompressedBytes, int maxArchiveEntries, long maxCompressionRatio, int ratioCheckMinBytes,
		int maxCellTextLength, int headerScanWindow) {

	public IngestionLimits {
		if (maxFileBytes <= 0 || maxRowsPerFile <= 0 || maxColumns <= 0 || maxSheets <= 0
				|| maxUncompressedBytes <= 0 || maxArchiveEntries <= 0 || maxCompressionRatio <= 0
				|| ratioCheckMinBytes < 0 || maxCellTextLength <= 0 || headerScanWindow <= 0) {
			throw new IllegalArgumentException("ingestion limits must all be positive");
		}
		if (maxFileBytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("maxFileBytes must fit in a single array for in-memory parsing");
		}
	}

	/**
	 * 20&nbsp;MB / 100k rows / 200x inflation is roughly an order of magnitude
	 * above a full ERP export of invoices for a mid-sized tenant and an order of
	 * magnitude below anything that would pressure the heap.
	 */
	public static IngestionLimits defaults() {
		return new IngestionLimits(20L * 1024L * 1024L, 100_000, 512, 32, 200L * 1024L * 1024L, 2_000, 200L, 4_096,
				4_096, 10);
	}

}
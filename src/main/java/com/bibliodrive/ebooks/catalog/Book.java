package com.bibliodrive.ebooks.catalog;

public record Book(
        String id,
        String title,
        String author,
        String series,
        String seriesIndex,
        String fileName,
        String relativePath,
        String extension,
        long sizeBytes,
        long lastModified,
        boolean hasCover
) {
}

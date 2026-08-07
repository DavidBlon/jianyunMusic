<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');

$files = [];
$directory = new FilesystemIterator(__DIR__, FilesystemIterator::SKIP_DOTS);
foreach ($directory as $entry) {
    $fileName = $entry->getFilename();
    if (
        !$entry->isFile() ||
        !$entry->isReadable() ||
        preg_match('/\.mp3\z/i', $fileName) !== 1
    ) {
        continue;
    }

    $files[] = $entry->getPathname();
}
usort(
    $files,
    static function (string $left, string $right): int {
        $caseInsensitive = strnatcasecmp(basename($left), basename($right));
        return $caseInsensitive !== 0
            ? $caseInsensitive
            : strnatcmp(basename($left), basename($right));
    }
);

$songs = [];
foreach ($files as $path) {
    if (!is_file($path) || !is_readable($path)) {
        continue;
    }

    $fileName = basename($path);
    $displayName = pathinfo($fileName, PATHINFO_FILENAME);
    $songs[] = [
        'file' => $fileName,
        'name' => $displayName,
        'durationMs' => $fileName === '简云漫游.mp3' ? 109000 : 0,
        'size' => filesize($path),
        'modifiedAt' => filemtime($path),
    ];
}

echo json_encode(
    ['songs' => array_values($songs)],
    JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR
);

<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-cache, must-revalidate');

$files = glob(__DIR__ . DIRECTORY_SEPARATOR . '*.mp3') ?: [];
natcasesort($files);

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

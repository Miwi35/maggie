<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

use Symfony\Component\Filesystem\Filesystem;

/**
 * Adds a request's executed lines to its journey's file, in the raw format of contract 2:
 * `{"journey": "<id>", "files": {"api/<path>": [lines, sorted, unique]}}`.
 *
 * One file per journey *and per php-fpm worker* (`requests/<slug>/<pid>.json`) rather than one
 * per request: a nightly makes tens of thousands of requests, each touching hundreds of files,
 * and a file each would weigh gigabytes. A worker serves one request at a time, so its file has a
 * single writer and needs no lock. JourneyCoverageMerger then folds the workers together.
 *
 * Only the API's own code: vendor/, var/ (the compiled container) and the tests are left out.
 */
final class JourneyCoverageWriter
{
    private const EXCLUDED = '~^(vendor|var|tests)/|/tests/~';

    private readonly Filesystem $filesystem;

    public function __construct(
        private readonly string $projectDir,
        private readonly string $coverageDir,
    ) {
        $this->filesystem = new Filesystem();
    }

    /** @param array<string, array<int, int>> $lines absolute file => line => hits */
    public function record(string $journey, array $lines, ?int $worker = null): void
    {
        $files = $this->ownExecutedLines($lines);
        if ([] === $files) {
            return;
        }

        $path = \sprintf('%s/requests/%s/%d.json', $this->coverageDir, Journey::slug($journey), $worker ?? getmypid());
        $previous = self::read($path);
        if (null !== $previous) {
            $files = self::union($previous['files'], $files);
        }

        // Written whole then renamed: a worker killed mid-write leaves the previous file intact.
        $this->filesystem->dumpFile($path, self::encode($journey, $files));
    }

    /**
     * @param array<string, list<int>> $a
     * @param array<string, list<int>> $b
     *
     * @return array<string, list<int>>
     */
    public static function union(array $a, array $b): array
    {
        foreach ($b as $file => $lines) {
            $merged = array_values(array_unique([...$a[$file] ?? [], ...$lines]));
            sort($merged);
            $a[$file] = $merged;
        }
        ksort($a);

        return $a;
    }

    /** @return array{journey: string, files: array<string, list<int>>}|null */
    public static function read(string $path): ?array
    {
        if (!is_file($path)) {
            return null;
        }

        $data = json_decode((string) file_get_contents($path), true);
        if (!\is_array($data) || !\is_string($data['journey'] ?? null) || !\is_array($data['files'] ?? null)) {
            return null;
        }

        $files = [];
        foreach ($data['files'] as $file => $lines) {
            if (\is_string($file) && \is_array($lines)) {
                $files[$file] = array_values(array_map('intval', $lines));
            }
        }

        return ['journey' => $data['journey'], 'files' => $files];
    }

    /** @param array<string, list<int>> $files */
    public static function encode(string $journey, array $files): string
    {
        return json_encode(['journey' => $journey, 'files' => (object) $files], \JSON_UNESCAPED_SLASHES | \JSON_THROW_ON_ERROR)."\n";
    }

    /**
     * @param array<string, array<int, int>> $lines
     *
     * @return array<string, list<int>> repo path => executed lines
     */
    private function ownExecutedLines(array $lines): array
    {
        $root = rtrim($this->projectDir, '/').'/';
        $files = [];

        foreach ($lines as $file => $hits) {
            if (!str_starts_with($file, $root)) {
                continue;
            }
            $relative = substr($file, \strlen($root));
            if (1 === preg_match(self::EXCLUDED, $relative)) {
                continue;
            }

            $executed = array_keys(array_filter($hits, static fn (int $count): bool => $count > 0));
            if ([] === $executed) {
                continue;
            }
            sort($executed);
            $files['api/'.$relative] = $executed;
        }
        ksort($files);

        return $files;
    }
}

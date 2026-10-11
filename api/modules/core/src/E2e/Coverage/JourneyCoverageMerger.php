<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

use Symfony\Component\Filesystem\Filesystem;
use Symfony\Component\Finder\Finder;

/**
 * Folds the per-worker files of JourneyCoverageWriter into one raw file per journey:
 * `<output>/<slug>.json` (contract 2). Reads without deleting, so it can run again later and
 * see everything recorded since; empties the output first, so nothing stale survives.
 */
final class JourneyCoverageMerger
{
    /** @return int the number of journeys written */
    public function merge(string $requestsDir, string $outputDir): int
    {
        $filesystem = new Filesystem();
        $filesystem->mkdir($outputDir);
        foreach (glob($outputDir.'/*.json') ?: [] as $stale) {
            $filesystem->remove($stale);
        }

        if (!is_dir($requestsDir)) {
            return 0;
        }

        /** @var array<string, array<string, list<int>>> $journeys */
        $journeys = [];
        foreach ((new Finder())->files()->in($requestsDir)->name('*.json')->sortByName() as $file) {
            $data = JourneyCoverageWriter::read($file->getPathname());
            $journey = Journey::valid($data['journey'] ?? null);
            if (null === $data || null === $journey) {
                continue;
            }
            $journeys[$journey] = JourneyCoverageWriter::union($journeys[$journey] ?? [], $data['files']);
        }

        foreach ($journeys as $journey => $files) {
            $filesystem->dumpFile(
                \sprintf('%s/%s.json', $outputDir, Journey::slug($journey)),
                JourneyCoverageWriter::encode($journey, $files),
            );
        }

        return \count($journeys);
    }
}

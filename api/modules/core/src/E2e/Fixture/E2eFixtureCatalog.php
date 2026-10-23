<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Fixture;

/**
 * Where the e2e fixture files are and how a loaded set is described to the
 * journeys. Shared by the seed (the whole world) and the journey users (a slice
 * of it), so both read the same files and name their rows the same way.
 */
final class E2eFixtureCatalog
{
    public function __construct(
        private readonly string $projectDir,
    ) {
    }

    /** @return list<string> */
    public function files(): array
    {
        $dir = $this->projectDir.'/fixtures/e2e';
        $files = glob($dir.'/*.yaml');

        if (false === $files || [] === $files) {
            throw new \RuntimeException(sprintf('No e2e fixture file found in %s.', $dir));
        }

        // Alphabetical, so the load order is the file names: 10-core.yaml
        // before 20-calendar.yaml, because a calendar belongs to a user.
        sort($files);

        return $files;
    }

    /**
     * The reference → id map journeys read instead of hard-coding ULIDs, which
     * carry a timestamp and so differ between two runs of identical data.
     *
     * @param array<string, object> $objects
     *
     * @return array<string, array{class: class-string, id: string}>
     */
    public function referencesOf(array $objects): array
    {
        $entries = [];
        foreach ($objects as $reference => $object) {
            if (!method_exists($object, 'getId')) {
                continue;
            }

            $entries[$reference] = [
                'class' => $object::class,
                'id' => (string) $object->getId(),
            ];
        }
        ksort($entries);

        return $entries;
    }
}

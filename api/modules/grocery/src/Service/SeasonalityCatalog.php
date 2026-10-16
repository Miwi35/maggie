<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\Product;

/**
 * The calendar of fruits and vegetables in season in metropolitan France
 * (MAG-494), read from `resources/seasonality.json` — its source and licence
 * are in the file.
 *
 * A product is recognised by its normalised name (lower case, no accents,
 * singular), one of the synonyms, or its Ciqual code. Only an exact match
 * counts: « Pomme de terre » is not a « Pomme ».
 */
final class SeasonalityCatalog
{
    private const FILE = __DIR__.'/../../resources/seasonality.json';

    /** @var list<array{name: string, kind: string, synonyms: list<string>, ciqualCodes: list<string>, months: list<int>}>|null */
    private ?array $produce = null;

    /** @var array<string, list<int>>|null */
    private ?array $byName = null;

    /** @var array<string, list<int>>|null */
    private ?array $byCiqual = null;

    /**
     * The months a product of that name (or Ciqual code) is in season, null when the calendar does not know it.
     *
     * @return list<int>|null
     */
    public function monthsFor(string $name, ?string $ciqualAlimCode = null): ?array
    {
        $this->load();
        \assert(null !== $this->byName && null !== $this->byCiqual);

        $code = null !== $ciqualAlimCode ? trim($ciqualAlimCode) : '';
        if ('' !== $code && isset($this->byCiqual[$code])) {
            return $this->byCiqual[$code];
        }

        return $this->byName[self::normalize($name)] ?? null;
    }

    /** @return list<int>|null */
    public function monthsForProduct(Product $product): ?array
    {
        return $this->monthsFor($product->getName(), $product->getCiqualAlimCode());
    }

    /**
     * Every fruit and vegetable of the calendar in season that month.
     *
     * @return list<array{name: string, kind: string, months: list<int>}>
     */
    public function inSeasonIn(int $month): array
    {
        $this->load();
        \assert(null !== $this->produce);

        $found = [];
        foreach ($this->produce as $entry) {
            if (\in_array($month, $entry['months'], true)) {
                $found[] = ['name' => $entry['name'], 'kind' => $entry['kind'], 'months' => $entry['months']];
            }
        }

        return $found;
    }

    /** « Tomates cerises » and « tomate cerise » are the same key: lower case, no accents, no plural, single spaces. */
    public static function normalize(string $name): string
    {
        $ascii = transliterator_transliterate('Any-Latin; Latin-ASCII; Lower()', trim($name));
        $ascii = false === $ascii ? mb_strtolower(trim($name)) : $ascii;
        $ascii = (string) preg_replace('/[^a-z0-9]+/', ' ', $ascii);

        $words = array_map(
            static fn (string $word): string => \strlen($word) > 3 && preg_match('/[sx]$/', $word) ? substr($word, 0, -1) : $word,
            array_filter(explode(' ', $ascii), static fn (string $word): bool => '' !== $word),
        );

        return implode(' ', $words);
    }

    private function load(): void
    {
        if (null !== $this->produce) {
            return;
        }

        /** @var array{produce: list<array{name: string, kind: string, synonyms: list<string>, ciqualCodes: list<string>, months: list<int>}>} $document */
        $document = json_decode((string) file_get_contents(self::FILE), true, 512, JSON_THROW_ON_ERROR);

        $this->produce = $document['produce'];
        $this->byName = [];
        $this->byCiqual = [];

        foreach ($this->produce as $entry) {
            foreach ([$entry['name'], ...$entry['synonyms']] as $name) {
                $this->byName[self::normalize($name)] ??= $entry['months'];
            }
            foreach ($entry['ciqualCodes'] as $code) {
                $this->byCiqual[$code] ??= $entry['months'];
            }
        }
    }
}

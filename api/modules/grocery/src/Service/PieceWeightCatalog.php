<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Entity\Product;

/**
 * The average weight of one piece of the common ingredients (MAG-647), read
 * from `resources/piece-weights.json`: the fallback for a product nobody gave
 * a weight. A product is recognised by its normalised name, or by what comes
 * before the first comma of a Ciqual name (« Oignon, cru » is « oignon »).
 */
final class PieceWeightCatalog
{
    private const FILE = __DIR__.'/../../resources/piece-weights.json';

    /** @var array<string, float>|null */
    private ?array $byName = null;

    public function gramsFor(string $name): ?float
    {
        $this->load();
        \assert(null !== $this->byName);

        $name = trim($name);
        $head = trim(explode(',', $name)[0]);

        return $this->byName[SeasonalityCatalog::normalize($name)]
            ?? $this->byName[SeasonalityCatalog::normalize($head)]
            ?? null;
    }

    public function gramsForProduct(Product $product): ?float
    {
        return $this->gramsFor($product->getName());
    }

    private function load(): void
    {
        if (null !== $this->byName) {
            return;
        }

        /** @var array{pieces: list<array{name: string, synonyms: list<string>, grams: int|float}>} $document */
        $document = json_decode((string) file_get_contents(self::FILE), true, 512, JSON_THROW_ON_ERROR);

        $this->byName = [];
        foreach ($document['pieces'] as $entry) {
            foreach ([$entry['name'], ...$entry['synonyms']] as $name) {
                $this->byName[SeasonalityCatalog::normalize($name)] ??= (float) $entry['grams'];
            }
        }
    }
}

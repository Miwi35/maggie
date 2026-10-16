<?php

declare(strict_types=1);

namespace Maggie\Grocery\Serializer;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\SeasonalLine;
use Maggie\Grocery\Service\SeasonCalendar;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * Fills `inSeason` (MAG-494) just before a product, a grocery item or a
 * recipe's ingredient is written out. The month moves, so it is neither
 * stored nor indexed: every read gets it from here, for the month of the
 * owner's timezone.
 */
final class SeasonNormalizer implements NormalizerInterface, NormalizerAwareInterface
{
    use NormalizerAwareTrait;

    private const ALREADY_CALLED = 'season_normalizer_already_called';

    public function __construct(
        private readonly SeasonCalendar $calendar,
    ) {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        \assert($data instanceof Product || $data instanceof SeasonalLine);

        $product = $data instanceof Product ? $data : $data->seasonalProduct();
        if (null !== $product) {
            $this->calendar->mark($product);
        }

        // Keyed by object: the product nested in a line is normalised after the line, and must be marked too.
        $context[self::ALREADY_CALLED][spl_object_id($data)] = true;

        return $this->normalizer->normalize($data, $format, $context);
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return ($data instanceof Product || $data instanceof SeasonalLine)
            && !isset($context[self::ALREADY_CALLED][spl_object_id($data)]);
    }

    /** @return array<class-string, bool> */
    public function getSupportedTypes(?string $format): array
    {
        return [Product::class => false, SeasonalLine::class => false];
    }
}

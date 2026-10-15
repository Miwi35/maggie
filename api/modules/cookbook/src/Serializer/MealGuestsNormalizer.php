<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Serializer;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Serializer\Exception\NotNormalizableValueException;
use Symfony\Component\Serializer\Normalizer\DenormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\DenormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\DenormalizerInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The guests of a meal on the wire (MAG-482).
 *
 * On the way out, the meal is handed its owner's default first, so `guests`
 * is always the number that counts — the meal's own, or the default — and
 * `guestsIsDefault` says which. Elasticsearch serves the meal's own number
 * only; the default is read here, so changing it needs no reindexing.
 *
 * On the way in, a number below one is a 400, like a day that is not a day,
 * rather than the 422 of a validation: the meal settings and the MCP tools
 * refuse it the same way.
 */
final class MealGuestsNormalizer implements NormalizerInterface, NormalizerAwareInterface, DenormalizerInterface, DenormalizerAwareInterface
{
    use NormalizerAwareTrait;
    use DenormalizerAwareTrait;

    private const ALREADY_CALLED = 'meal_guests_normalizer_already_called';

    public function __construct(
        private readonly MealGuests $mealGuests,
        private readonly Security $security,
    ) {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        \assert($data instanceof Meal);

        // Every meal served is the logged-in user's: knowing it spares loading
        // the agenda of a meal Elasticsearch handed back.
        $user = $this->security->getUser();
        $this->mealGuests->resolve($data, $user instanceof User ? $user : null);

        return $this->normalizer->normalize($data, $format, $context + [self::ALREADY_CALLED => true]);
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return $data instanceof Meal && !isset($context[self::ALREADY_CALLED]);
    }

    public function denormalize(mixed $data, string $type, ?string $format = null, array $context = []): mixed
    {
        if (\is_array($data) && \array_key_exists('guests', $data) && null !== $data['guests']
            && (!\is_int($data['guests']) || $data['guests'] < 1)) {
            throw NotNormalizableValueException::createForUnexpectedDataType('guests must be a whole number of at least 1, or null for the default.', $data['guests'], ['int', 'null'], 'guests', true);
        }

        return $this->denormalizer->denormalize($data, $type, $format, $context + [self::ALREADY_CALLED => true]);
    }

    public function supportsDenormalization(mixed $data, string $type, ?string $format = null, array $context = []): bool
    {
        return Meal::class === $type && !isset($context[self::ALREADY_CALLED]);
    }

    /** @return array<class-string, bool> */
    public function getSupportedTypes(?string $format): array
    {
        return [Meal::class => false];
    }
}

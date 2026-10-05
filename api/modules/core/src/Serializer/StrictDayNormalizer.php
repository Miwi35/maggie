<?php

declare(strict_types=1);

namespace Maggie\Core\Serializer;

use Symfony\Component\DependencyInjection\Attribute\AsDecorator;
use Symfony\Component\DependencyInjection\Attribute\AutowireDecorated;
use Symfony\Component\Serializer\Exception\NotNormalizableValueException;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Serializer\Normalizer\DenormalizerInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * Makes a field declared as a day refuse anything that is not one.
 *
 * `DateTimeImmutable::createFromFormat('!Y-m-d', '2026-13-45')` does not fail:
 * it rolls the thirteenth month and the forty-fifth day over and hands back
 * 2027-02-14. Symfony's DateTimeNormalizer only looks at whether the parse
 * returned `false`, so a day nobody could have meant was accepted and the row
 * was written four months away — the very shape of bug MAG-251 is about.
 *
 * The check is a round trip: what came out, formatted back as a day, has to be
 * the string that came in. Nothing else about the serializer changes — a
 * property is only held to it by asking for {@see self::DAY_FORMAT}, as
 * `Meal::$date` does.
 */
#[AsDecorator('serializer.normalizer.datetime')]
final class StrictDayNormalizer implements DenormalizerInterface, NormalizerInterface
{
    /** The denormalization format a day-typed property declares. */
    public const DAY_FORMAT = '!Y-m-d';

    public function __construct(
        #[AutowireDecorated]
        private readonly DateTimeNormalizer $inner,
    ) {
    }

    public function denormalize(mixed $data, string $type, ?string $format = null, array $context = []): mixed
    {
        $denormalized = $this->inner->denormalize($data, $type, $format, $context);

        if (self::DAY_FORMAT !== ($context[DateTimeNormalizer::FORMAT_KEY] ?? null)
            || !\is_string($data)
            || !$denormalized instanceof \DateTimeInterface
            || $denormalized->format('Y-m-d') === $data
        ) {
            return $denormalized;
        }

        throw NotNormalizableValueException::createForUnexpectedDataType(\sprintf('"%s" is not a day. Use YYYY-MM-DD, and a day that exists.', $data), $data, ['string'], $context['deserialization_path'] ?? null, true);
    }

    public function supportsDenormalization(mixed $data, string $type, ?string $format = null, array $context = []): bool
    {
        return $this->inner->supportsDenormalization($data, $type, $format, $context);
    }

    /** Narrowed to what the decorated normalizer returns — a date is never a list. */
    public function normalize(mixed $data, ?string $format = null, array $context = []): string|int|float
    {
        return $this->inner->normalize($data, $format, $context);
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return $this->inner->supportsNormalization($data, $format, $context);
    }

    /** @return array<class-string|'*'|'object'|string, bool|null> */
    public function getSupportedTypes(?string $format): array
    {
        return $this->inner->getSupportedTypes($format);
    }
}

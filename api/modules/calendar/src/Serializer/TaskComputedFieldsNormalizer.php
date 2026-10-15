<?php

declare(strict_types=1);

namespace Maggie\Calendar\Serializer;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\TaskUrgency;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * Fills `overdueDays` (MAG-405), `urgency` and `orderScore` (MAG-406) just
 * before a task is written out. The day moves, so they are neither stored nor
 * indexed: every read — item, collection, the day's view — gets them from here.
 */
final class TaskComputedFieldsNormalizer implements NormalizerInterface, NormalizerAwareInterface
{
    use NormalizerAwareTrait;

    private const ALREADY_CALLED = 'task_computed_fields_normalizer_already_called';

    public function __construct(
        private readonly TaskUrgency $urgency,
    ) {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        \assert($data instanceof Task);

        $this->urgency->mark($data);

        return $this->normalizer->normalize($data, $format, $context + [self::ALREADY_CALLED => true]);
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return $data instanceof Task && !isset($context[self::ALREADY_CALLED]);
    }

    /** @return array<class-string, bool> */
    public function getSupportedTypes(?string $format): array
    {
        return [Task::class => false];
    }
}

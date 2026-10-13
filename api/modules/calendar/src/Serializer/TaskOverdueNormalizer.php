<?php

declare(strict_types=1);

namespace Maggie\Calendar\Serializer;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\TaskOverdue;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * Fills `overdueDays` just before a task is written out (MAG-405). The day
 * moves, so the number is neither stored nor indexed: every read — item,
 * collection, the day's view — gets it from here.
 */
final class TaskOverdueNormalizer implements NormalizerInterface, NormalizerAwareInterface
{
    use NormalizerAwareTrait;

    private const ALREADY_CALLED = 'task_overdue_normalizer_already_called';

    public function __construct(
        private readonly TaskOverdue $overdue,
    ) {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        \assert($data instanceof Task);

        $this->overdue->mark($data);

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

<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Routine;

/**
 * Applies the fields a client sent to a routine (MAG-511): those present
 * only, so a creation and a PATCH read the same way. Refusals are
 * {@see \DomainException}s naming the field, the caller's to read.
 */
final class RoutineInput
{
    /** @param array<string, mixed> $fields */
    public static function apply(Routine $routine, array $fields, bool $creating = false): void
    {
        try {
            if ($creating || \array_key_exists('title', $fields)) {
                if (!\is_string($fields['title'] ?? null)) {
                    throw new \InvalidArgumentException('title: a routine needs a title, 255 characters at most.');
                }
                $routine->setTitle($fields['title']);
            }

            if ($creating || \array_key_exists('anchor', $fields) || \array_key_exists('anchorTime', $fields)) {
                $anchor = $fields['anchor'] ?? ($creating ? null : $routine->getAnchor());
                if (!\is_string($anchor)) {
                    throw new \InvalidArgumentException(\sprintf('anchor: one of %s.', implode(', ', Routine::ANCHORS)));
                }
                // A routine moved off its hour drops it; one moved to an hour must say which.
                $anchorTime = \array_key_exists('anchorTime', $fields)
                    ? $fields['anchorTime']
                    : (Routine::ANCHOR_TIME === $anchor && !$creating ? $routine->getAnchorTime() : null);
                if (null !== $anchorTime && !\is_string($anchorTime)) {
                    throw new \InvalidArgumentException('anchorTime: a time "HH:MM".');
                }
                $routine->setAnchor($anchor, $anchorTime);
            }

            if ($creating || \array_key_exists('recurrence', $fields)) {
                $recurrence = $fields['recurrence'] ?? ($creating ? ['kind' => Routine::DAILY] : null);
                if (!\is_array($recurrence)) {
                    throw new \InvalidArgumentException(\sprintf('recurrence: an object {kind: %s}.', implode(' | ', Routine::KINDS)));
                }
                $routine->setRecurrence($recurrence);
            }

            foreach (['active' => $routine->setActive(...), 'remind' => $routine->setRemind(...)] as $field => $set) {
                if (!\array_key_exists($field, $fields)) {
                    continue;
                }
                if (!\is_bool($fields[$field])) {
                    throw new \InvalidArgumentException("{$field}: true or false.");
                }
                $set($fields[$field]);
            }
        } catch (\InvalidArgumentException $e) {
            throw new \DomainException($e->getMessage(), 0, $e);
        }
    }
}

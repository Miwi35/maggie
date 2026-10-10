<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Attribute;

#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_METHOD)]
final class IndexedField
{
    /**
     * @param array<string, mixed> $properties For nested/object types
     * @param ?string              $format     Elasticsearch date format, in Java notation
     *                                         (`yyyy-MM-dd`). A `date` field left without one
     *                                         is read with the default formats, which accept a
     *                                         plain day *and* an instant — so a field that is
     *                                         only ever a day silently takes instants too.
     * @param ?string              $dayField   On an instant, the day field a document holds
     *                                         instead when it has no instant — an all-day event's
     *                                         `startDate` for `startAt`. A range filter on the
     *                                         instant then also matches those documents by their
     *                                         day ({@see \Maggie\Core\Time\DayBound}).
     */
    public function __construct(
        public readonly string $type = 'text',
        public readonly ?string $name = null,
        public readonly ?float $boost = null,
        public readonly ?string $analyzer = null,
        public readonly bool $keyword = false,
        public readonly array $properties = [],
        public readonly ?string $format = null,
        public readonly ?string $dayField = null,
    ) {
    }
}

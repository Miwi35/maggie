<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp;

use Maggie\Health\Entity\BodyMeasurement;

/** What the MCP tool says about a body measurement: the API's shape plus the French source label. */
final class BodyMeasurementJson
{
    /** @return array<string, mixed> */
    public static function of(BodyMeasurement $measurement): array
    {
        return [
            'id' => (string) $measurement->getId(),
            'measuredAt' => $measurement->getMeasuredAt()->format(\DATE_ATOM),
            'weightKg' => $measurement->getWeightKg(),
            'bodyFatPercent' => $measurement->getBodyFatPercent(),
            'waistCm' => $measurement->getWaistCm(),
            'source' => $measurement->getSource()->value,
            'sourceLabel' => $measurement->getSource()->label(),
            'externalId' => $measurement->getExternalId(),
            'excluded' => $measurement->isExcluded(),
        ];
    }

    /**
     * @param list<BodyMeasurement> $measurements
     *
     * @return list<array<string, mixed>>
     */
    public static function list(array $measurements): array
    {
        return array_map(self::of(...), $measurements);
    }
}

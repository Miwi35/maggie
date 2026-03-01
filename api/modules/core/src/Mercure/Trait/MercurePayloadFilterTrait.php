<?php

namespace Maggie\Core\Mercure\Trait;

/**
 * Filters a Mercure payload to include only fields that changed.
 *
 * Used by entities in toMercurePayload() to support differential updates.
 */
trait MercurePayloadFilterTrait
{
    /**
     * @param array<string, mixed>  $payload           Full payload
     * @param string[]|null         $changedProperties Doctrine property names, or null for full payload
     * @param array<string, string> $propertyMap       Doctrine prop → payload key (for mismatches)
     *
     * @return array<string, mixed>
     */
    protected static function filterPayload(array $payload, ?array $changedProperties, array $propertyMap = []): array
    {
        if ($changedProperties === null) {
            return $payload;
        }

        // Map Doctrine property names to payload keys
        $payloadKeys = [];
        foreach ($changedProperties as $prop) {
            $payloadKeys[] = $propertyMap[$prop] ?? $prop;
        }

        $filtered = array_intersect_key($payload, array_flip($payloadKeys));

        // Fallback to full payload if no keys matched (e.g. unmapped collection changes)
        return $filtered !== [] ? $filtered : $payload;
    }
}

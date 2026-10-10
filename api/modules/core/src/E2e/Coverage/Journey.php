<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Coverage;

/**
 * The e2e journey a request belongs to: its repo path (`e2e/web/tests/chat.spec.ts`), sent in
 * `X-E2E-Journey` by the journeys and passed on by the agent (spec « Sélection e2e par
 * couverture », contract 1). Same rules as the agent's `app/e2e_coverage`.
 */
final class Journey
{
    public const HEADER = 'X-E2E-Journey';

    /** The id if the header holds one; anything else never reaches a file name. */
    public static function valid(?string $value): ?string
    {
        if (null === $value || 1 !== preg_match('~^[A-Za-z0-9_./-]{1,200}$~', $value) || str_contains($value, '..')) {
            return null;
        }

        return $value;
    }

    /** The raw file's name: `/` and `.` become `_` (contract 2). */
    public static function slug(string $journey): string
    {
        return str_replace(['/', '.'], '_', $journey);
    }
}

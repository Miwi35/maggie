<?php

declare(strict_types=1);

namespace Maggie\Core\Identifier;

use Symfony\Component\Uid\Ulid;

/**
 * The spelling of an id that Elasticsearch documents and Mercure topics use.
 *
 * Doctrine finds a ULID whatever its case or notation, so a delete command
 * carrying "01m3t8pp…" succeeds in the database; the index and the topics are
 * keyed on the canonical base32 form and would miss it.
 */
final class CanonicalId
{
    public static function of(string $id): string
    {
        return Ulid::isValid($id) ? Ulid::fromString($id)->toBase32() : $id;
    }
}

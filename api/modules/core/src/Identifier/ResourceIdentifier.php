<?php

declare(strict_types=1);

namespace Maggie\Core\Identifier;

use Symfony\Component\Uid\Ulid;

/**
 * Reads the identifier out of whatever a client put in a query parameter.
 *
 * Clients send the IRI — "/api/accounts/01ARZ3NDEK…" — because that is what
 * the provider handed them, and re-prefixing one is its own regression
 * (c359b43). A bare ULID is accepted too: the identifier is the last segment
 * either way.
 *
 * Shared by UlidRelationFilter and ElasticsearchFilterTranslator because the
 * two of them answer the same request through different engines, and this
 * ticket exists because two implementations of one contract drifted apart.
 */
final class ResourceIdentifier
{
    /**
     * The identifier the value names, or null when it names none — an empty
     * string, an array from `?account[]=…`, a hand-typed id from a stale
     * link.
     *
     * Callers must treat null as "matches nothing", never as "no filter
     * sent": answering a narrowed request with the whole collection is how
     * one account's history ends up on another account's screen.
     */
    public static function fromRequestValue(mixed $value): ?Ulid
    {
        if (!\is_string($value) || $value === '') {
            return null;
        }

        $candidate = str_contains($value, '/')
            ? substr($value, strrpos($value, '/') + 1)
            : $value;

        return Ulid::isValid($candidate) ? Ulid::fromString($candidate) : null;
    }
}

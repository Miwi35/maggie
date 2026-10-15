<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

/**
 * A value object an entity indexes as one object field — an embeddable. The
 * hydrator rebuilds it from that field when it serves the entity from
 * Elasticsearch, instead of setting a bare array on a typed property.
 */
interface SearchDocumentValue
{
    /** @param array<string, mixed> $document the object field, as the entity's `toSearchDocument()` wrote it */
    public static function fromSearchDocument(array $document): static;
}

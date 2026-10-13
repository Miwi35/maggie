<?php

declare(strict_types=1);

namespace Maggie\Core\Doctrine;

use ApiPlatform\Doctrine\Orm\Extension\QueryCollectionExtensionInterface;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Maggie\Core\Elasticsearch\IndexMetadataReader;

/**
 * The Doctrine answer of a collection is the Elasticsearch one's twin (and its fallback):
 * `GET /api/events` holds the events, not the meals, which have their own collection.
 */
final class OwnRowsExtension implements QueryCollectionExtensionInterface
{
    public function applyToCollection(QueryBuilder $queryBuilder, QueryNameGeneratorInterface $queryNameGenerator, string $resourceClass, ?Operation $operation = null, array $context = []): void
    {
        IndexMetadataReader::restrictToOwnRows($queryBuilder, $queryBuilder->getRootAliases()[0], $resourceClass);
    }
}

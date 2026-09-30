<?php

declare(strict_types=1);

namespace Maggie\Core\Doctrine\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Maggie\Core\Identifier\ResourceIdentifier;

/**
 * Narrows a collection to one related entity, addressed by its IRI.
 *
 * API Platform's own SearchFilter cannot do this here. Every entity in Maggie
 * is keyed by a ULID, stored in Postgres as a `uuid` column through Symfony's
 * `ulid` Doctrine type. SearchFilter binds the identifier it extracted with no
 * type, so Postgres receives the ULID's base32 spelling — "01ARZ3NDEK…" —
 * where it expects a UUID, and the request ends as a 500. Not a filtered
 * list, not an unfiltered one: an error page.
 *
 * That is why no resource declared a relation filter, and why the mobile app
 * sent `accountId`, a parameter nothing declared. It worked, by accident:
 * ElasticsearchFilterTranslator turned any unrecognised string parameter into
 * a term query, and the index happens to hold the relation under exactly that
 * name. So the account screen was right in production and wrong everywhere
 * the Doctrine path served the collection — tests included, which is how a
 * contract this thin survived. Declaring the filter makes both paths agree
 * for the same reason rather than by coincidence.
 *
 * The filter takes the IRI, because that is what the provider hands the
 * clients and re-prefixing it is its own regression (c359b43). A bare ULID is
 * accepted too: the identifier is the last segment either way.
 */
final class UlidRelationFilter extends AbstractFilter
{
    protected function filterProperty(
        string $property,
        mixed $value,
        QueryBuilder $queryBuilder,
        QueryNameGeneratorInterface $queryNameGenerator,
        string $resourceClass,
        ?Operation $operation = null,
        array $context = [],
    ): void {
        if (!$this->isPropertyEnabled($property, $resourceClass)
            || !$this->isPropertyMapped($property, $resourceClass, true)
        ) {
            return;
        }

        $alias = $queryBuilder->getRootAliases()[0];
        $parameter = $queryNameGenerator->generateParameterName($property);
        $join = $queryNameGenerator->generateJoinAlias($property);

        // Reaching here means the parameter was sent: API Platform only calls
        // filterProperty for parameters present in the request. So anything
        // that is not a ULID names nothing — an empty value, an array from
        // `?account[]=…`, a hand-typed id from a stale link.
        $ulid = ResourceIdentifier::fromRequestValue($value);

        if (null === $ulid) {
            // A request that names nothing must come back with nothing.
            // Skipping the clause instead would answer a narrowed request
            // with the whole collection, which is the failure this ticket is
            // about — and here it would put one account's transactions on
            // another account's screen.
            $queryBuilder->andWhere('1 = 0');

            return;
        }

        $queryBuilder
            ->join(sprintf('%s.%s', $alias, $property), $join)
            ->andWhere(sprintf('%s.id = :%s', $join, $parameter))
            // The type is the point of this class: without it Doctrine binds
            // a string and Postgres refuses to compare it to a uuid.
            ->setParameter($parameter, $ulid, 'ulid');
    }

    /**
     * @return array<string, array<string, mixed>>
     */
    public function getDescription(string $resourceClass): array
    {
        $description = [];

        foreach (array_keys($this->properties ?? []) as $property) {
            $description[(string) $property] = [
                'property' => $property,
                'type' => 'string',
                'required' => false,
                'description' => sprintf('Restrict to one %s, given as its IRI.', $property),
                'openapi' => [
                    'description' => sprintf('Restrict to one %s, given as its IRI.', $property),
                    'example' => sprintf('/api/%ss/01ARZ3NDEKTSV4RRFFQ69G5FAV', $property),
                ],
            ];
        }

        return $description;
    }
}

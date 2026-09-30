<?php

declare(strict_types=1);

namespace Maggie\Core\Doctrine\Filter;

use ApiPlatform\Doctrine\Orm\Filter\AbstractFilter;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Symfony\Component\Uid\Ulid;

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
 * fell back to sending `accountId`, which nothing declared and API Platform
 * therefore dropped — so every account screen showed every account's
 * transactions.
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
        if (!\is_string($value) || $value === '') {
            return;
        }

        if (!$this->isPropertyEnabled($property, $resourceClass)
            || !$this->isPropertyMapped($property, $resourceClass, true)
        ) {
            return;
        }

        $alias = $queryBuilder->getRootAliases()[0];
        $parameter = $queryNameGenerator->generateParameterName($property);
        $join = $queryNameGenerator->generateJoinAlias($property);

        $ulid = self::identifierOf($value);

        if ($ulid === null) {
            // A value that names nothing must return nothing. Ignoring it
            // would answer a narrowed request with the whole collection,
            // which is the failure this whole ticket is about — and here it
            // would leak one account's transactions onto another's screen.
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

    /**
     * "/api/accounts/01ARZ3NDEK…" or "01ARZ3NDEK…" → the Ulid; null when the
     * value is not one.
     */
    private static function identifierOf(string $value): ?Ulid
    {
        $candidate = str_contains($value, '/')
            ? substr($value, strrpos($value, '/') + 1)
            : $value;

        return Ulid::isValid($candidate) ? Ulid::fromString($candidate) : null;
    }
}

<?php

namespace Maggie\Core\Doctrine;

use ApiPlatform\Doctrine\Orm\Extension\QueryCollectionExtensionInterface;
use ApiPlatform\Doctrine\Orm\Extension\QueryItemExtensionInterface;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;

final class CurrentUserExtension implements QueryCollectionExtensionInterface, QueryItemExtensionInterface
{
    public function __construct(
        private readonly Security $security,
    ) {
    }

    public function applyToCollection(QueryBuilder $queryBuilder, QueryNameGeneratorInterface $queryNameGenerator, string $resourceClass, ?Operation $operation = null, array $context = []): void
    {
        $this->addWhere($queryBuilder, $resourceClass);
    }

    public function applyToItem(QueryBuilder $queryBuilder, QueryNameGeneratorInterface $queryNameGenerator, string $resourceClass, array $identifiers, ?Operation $operation = null, array $context = []): void
    {
        $this->addWhere($queryBuilder, $resourceClass);
    }

    private function addWhere(QueryBuilder $queryBuilder, string $resourceClass): void
    {
        $user = $this->security->getUser();

        if (!$user instanceof User) {
            return;
        }

        $rootAlias = $queryBuilder->getRootAliases()[0];

        if (is_subclass_of($resourceClass, OwnedByUserInterface::class)) {
            $queryBuilder->andWhere(sprintf('%s.user = :current_user', $rootAlias));
            $queryBuilder->setParameter('current_user', $user->getId(), 'ulid');

            return;
        }

        if (is_subclass_of($resourceClass, OwnedThroughInterface::class)) {
            $parentRelation = $resourceClass::getOwnerRelation();
            $parentAlias = 'owner_filter_' . $parentRelation;
            $queryBuilder->join(sprintf('%s.%s', $rootAlias, $parentRelation), $parentAlias);
            $queryBuilder->andWhere(sprintf('%s.user = :current_user', $parentAlias));
            $queryBuilder->setParameter('current_user', $user->getId(), 'ulid');
        }
    }
}

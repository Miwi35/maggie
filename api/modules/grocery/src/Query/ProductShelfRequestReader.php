<?php

declare(strict_types=1);

namespace Maggie\Grocery\Query;

use Maggie\Core\Entity\User;
use Maggie\Core\Identifier\ResourceIdentifier;
use Maggie\Grocery\Repository\AisleRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Uid\Ulid;

/**
 * `store=<IRI|id>`, `aisle=<IRI|id|none>` and `order[name|aisle|stockState]` on the
 * products and the ingredients.
 *
 * An aisle belongs to one store, so it is only meaningful next to it: `aisle`
 * and `order[aisle]` without `store` are a 400, and so is an aisle of another
 * store than the one asked. A store or an aisle that does not exist (or is not
 * the user's) is an empty answer.
 */
class ProductShelfRequestReader
{
    public const NO_AISLE = 'none';

    public function __construct(
        private readonly Security $security,
        private readonly AisleRepository $aisleRepository,
    ) {
    }

    /**
     * @param array<string, mixed> $filters the query parameters
     *
     * @throws BadRequestHttpException
     */
    public function read(array $filters): ProductShelfRequest
    {
        $matchesNothing = false;

        $store = null;
        if (\array_key_exists('store', $filters)) {
            $store = ResourceIdentifier::fromRequestValue($filters['store']);
            $matchesNothing = null === $store;
        }

        $aisle = null;
        $withoutAisle = false;
        if (\array_key_exists('aisle', $filters)) {
            if (!\array_key_exists('store', $filters)) {
                throw new BadRequestHttpException('The "aisle" filter needs a "store": an aisle belongs to one store.');
            }

            if (self::NO_AISLE === $filters['aisle']) {
                $withoutAisle = true;
            } else {
                $aisle = ResourceIdentifier::fromRequestValue($filters['aisle']);
                $matchesNothing = $matchesNothing || null === $aisle || !$this->aisleIsInStore($aisle, $store);
            }
        }

        $order = [];
        if (\is_array($filters['order'] ?? null)) {
            foreach ($filters['order'] as $field => $direction) {
                $direction = \is_string($direction) ? strtolower($direction) : '';
                if (!\in_array($field, ProductShelfRequest::SORTS, true) || !\in_array($direction, ['asc', 'desc'], true)) {
                    continue;
                }
                if ('aisle' === $field && !\array_key_exists('store', $filters)) {
                    throw new BadRequestHttpException('Sorting by "aisle" needs a "store": the aisles of two stores have no common order.');
                }
                $order[] = ['field' => $field, 'direction' => $direction];
            }
        }

        return new ProductShelfRequest($store, $aisle, $withoutAisle, $order, $matchesNothing);
    }

    private function aisleIsInStore(Ulid $aisleId, ?Ulid $storeId): bool
    {
        $user = $this->security->getUser();
        $aisle = $this->aisleRepository->find($aisleId);
        if (!$user instanceof User || null === $aisle || (string) $aisle->getUser()->getId() !== (string) $user->getId()) {
            return false;
        }

        if (null !== $storeId && (string) $aisle->getStore()->getId() !== (string) $storeId) {
            throw new BadRequestHttpException(sprintf('The aisle "%s" is in the store "%s", not in the store asked.', $aisle->getName(), $aisle->getStore()->getName()));
        }

        return true;
    }
}

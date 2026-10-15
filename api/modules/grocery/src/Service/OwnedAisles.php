<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Exception\InvalidAisleException;
use Maggie\Grocery\Repository\AisleRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\UseCase\CreateAisle;
use Symfony\Component\Uid\Ulid;

/**
 * Finds the user's stores and aisles from what a client or Maggie names them
 * by: an id, an IRI, or — for an aisle — its name in the store, which creates
 * it last on the way when the store has none by that name. Anything that is
 * not the user's is « not found », whoever owns it.
 */
class OwnedAisles
{
    public function __construct(
        private readonly AisleRepository $aisleRepository,
        private readonly StoreRepository $storeRepository,
        private readonly CreateAisle $createAisle,
    ) {
    }

    /** @throws InvalidAisleException */
    public function store(User $user, string $ref): Store
    {
        $id = self::bareId($ref, 'stores');
        $store = Ulid::isValid($id) ? $this->storeRepository->find($id) : null;
        if (null === $store || (string) $store->getUser()->getId() !== (string) $user->getId()) {
            throw new InvalidAisleException(sprintf('Store not found: %s', $ref));
        }

        return $store;
    }

    /** @throws InvalidAisleException */
    public function aisle(User $user, string $ref): Aisle
    {
        $id = self::bareId($ref, 'aisles');
        $aisle = Ulid::isValid($id) ? $this->aisleRepository->find($id) : null;
        if (null === $aisle || (string) $aisle->getUser()->getId() !== (string) $user->getId()) {
            throw new InvalidAisleException(sprintf('Aisle not found: %s', $ref));
        }

        return $aisle;
    }

    /**
     * The aisle of this store an id or a name points at; a name the store
     * does not have yet becomes a new aisle, last on the way. Flushes then.
     *
     * @throws InvalidAisleException when the id is an aisle of another store
     */
    public function aisleOfStore(User $user, Store $store, string $ref): Aisle
    {
        $id = self::bareId($ref, 'aisles');
        if (Ulid::isValid($id)) {
            $aisle = $this->aisle($user, $ref);
            if ($aisle->getStore() !== $store) {
                throw new InvalidAisleException(sprintf('The aisle "%s" is in the store "%s", not in "%s".', $aisle->getName(), $aisle->getStore()->getName(), $store->getName()));
            }

            return $aisle;
        }

        return $this->aisleRepository->findOneByStoreAndName($store, trim($ref))
            ?? $this->createAisle->execute($user, $store, $ref);
    }

    private static function bareId(string $ref, string $collection): string
    {
        $prefix = '/api/'.$collection.'/';

        return str_starts_with($ref, $prefix) ? substr($ref, \strlen($prefix)) : $ref;
    }
}

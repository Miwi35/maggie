<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\MoveToFallbackCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Grocery\Repository\StoreRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class MoveToFallbackHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly StoreRepository $storeRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(MoveToFallbackCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $store = $this->storeRepository->find($command->storeId);
        if (null === $store || (string) $store->getUser()->getId() !== $command->userId) {
            throw new \DomainException("Store not found: {$command->storeId}");
        }

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        foreach ($list->getItems() as $item) {
            if ($item->isChecked()) {
                continue;
            }
            if (null === $item->getStore() || (string) $item->getStore()->getId() !== (string) $store->getId()) {
                continue;
            }

            $product = $item->getProduct();
            $fallback = $product?->getFallbackStore();
            if (null !== $fallback) {
                $item->setStore($fallback);
            }
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}

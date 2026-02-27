<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\MoveToFallbackCommand;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\Repository\StoreRepository;
use Maggie\Core\Repository\UserRepository;
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

    /**
     * @return array<array{itemId: string, label: string, newStore: string}>
     */
    public function __invoke(MoveToFallbackCommand $command): array
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $store = $this->storeRepository->find($command->storeId)
            ?? throw new \DomainException("Store not found: {$command->storeId}");

        $list = $this->groceryListRepository->findOrCreateForUser($user);
        $moved = [];

        foreach ($list->getItems() as $item) {
            if ($item->isChecked()) {
                continue;
            }
            if ($item->getStore() === null || (string) $item->getStore()->getId() !== (string) $store->getId()) {
                continue;
            }

            $product = $item->getProduct();
            $fallback = $product?->getFallbackStore();
            if ($fallback !== null) {
                $item->setStore($fallback);
                $moved[] = [
                    'itemId' => (string) $item->getId(),
                    'label' => $item->getLabel(),
                    'newStore' => $fallback->getName(),
                ];
            }
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $moved;
    }
}

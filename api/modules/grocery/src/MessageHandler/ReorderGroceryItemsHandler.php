<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\ReorderGroceryItemsCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ReorderGroceryItemsHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(ReorderGroceryItemsCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $items = [];
        foreach ($command->items as $entry) {
            $item = $this->em->find(GroceryItem::class, $entry['id']);
            if (null === $item || (string) $item->getGroceryList()->getUser()->getId() !== $command->userId) {
                throw new \DomainException("Grocery item not found: {$entry['id']}");
            }
            $items[] = [$item, $entry['position']];
        }

        foreach ($items as [$item, $position]) {
            $item->setPosition($position);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}

<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\EndErrandCommand;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class EndErrandHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(EndErrandCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        // Remove all checked (bought) items
        foreach ($list->getItems()->toArray() as $item) {
            if ($item->isChecked()) {
                $list->removeItem($item);
                $this->em->remove($item);
            }
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}
